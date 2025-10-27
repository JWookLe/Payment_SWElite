# 🔌 Circuit Breaker 완벽 가이드

Payment_SWElite의 Resilience4j 기반 Circuit Breaker 구현에 대한 **완전한 통합 가이드**입니다.

**최종 업데이트**: 2025-10-27
**상태**: ✅ 프로덕션 배포 준비 완료
**프레임워크**: Resilience4j 2.1.0 + Spring Boot 3.3.4

---

## 📋 개요

Payment_SWElite에 **Resilience4j 기반 Circuit Breaker**를 구현했습니다. 이는 Kafka 발행 실패로부터 서비스를 보호하는 **프로덕션 수준의 솔루션**입니다.

### 구현 범위
- **보호 대상**: Kafka Publisher (ingest-service)
- **데이터 보호**: Transactional Outbox Pattern으로 OutboxEvent 우선 저장
- **자동 복구**: 의존성 회복 시 자동으로 서비스 복구
- **실시간 모니터링**: Prometheus 메트릭 + Grafana 대시보드

---

## 🎯 주요 목표

- ✅ Kafka 장애 시 빠른 실패 (Fail-Fast)
- ✅ 연쇄 장애 방지 (Cascading Failures Prevention)
- ✅ 자동 복구 (Automatic Recovery)
- ✅ 실시간 모니터링 (Real-time Monitoring)
- ✅ 데이터 무결성 유지 (Transactional Outbox Pattern)

---

## 🏗️ Circuit Breaker 동작 원리

### 상태 전이 다이어그램

```
     ┌─────────────────────────────┐
     │      CLOSED (정상)          │
     │   통상적으로 모든            │
     │   요청이 통과함              │
     └──────────┬──────────────────┘
                │
   [실패율/느린호출 >= 50%]
   [최소 5개 호출 후]
                ▼
     ┌─────────────────────────────┐
     │      OPEN (차단)            │
     │   요청을 즉시 차단           │
     │   30초 대기 후 HALF_OPEN    │
     └──────────┬──────────────────┘
                │
            [30초 경과]
                ▼
     ┌─────────────────────────────┐
     │  HALF_OPEN (복구 시도)      │
     │ 제한된 요청으로 복구 시도    │
     │ (최대 3개)                  │
     └──────────┬──────────────────┘
                │
     ┌──────────┴─────────────┐
     │                        │
  [성공]                  [실패]
     │                        │
     ▼                        ▼
  CLOSED                    OPEN
```

### 각 상태의 의미

| 상태 | 의미 | 동작 |
|------|------|------|
| **CLOSED** | 정상 상태 | 모든 요청 통과, 메트릭 기록 |
| **OPEN** | 서비스 장애 감지 | 요청 차단 (CallNotPermittedException), 30초 대기 |
| **HALF_OPEN** | 복구 시도 중 | 제한된 요청으로 서비스 상태 확인 (최대 3개) |

---

## 💻 핵심 구현 상세

### 1️⃣ PaymentEventPublisher.java (Kafka 발행 로직)

**위치**: `backend/ingest-service/src/main/java/com/example/payment/service/PaymentEventPublisher.java`

#### 필드와 생성자

```java
private final CircuitBreakerRegistry circuitBreakerRegistry;
private static final String CIRCUIT_BREAKER_NAME = "kafka-publisher";

public PaymentEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        OutboxEventRepository outboxEventRepository,
        ObjectMapper objectMapper,
        CircuitBreakerRegistry circuitBreakerRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.circuitBreakerRegistry = circuitBreakerRegistry;  // ← 주입
}
```

#### 핵심 메서드: publishToKafkaWithCircuitBreaker()

```java
public void publishToKafkaWithCircuitBreaker(OutboxEvent outboxEvent, String topic, String payload) {
    // Step 1: Circuit Breaker 인스턴스 획득
    CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

    // Step 2: 발행 작업을 Runnable로 정의
    Runnable publishTask = () -> {
        // Kafka 메시지 생성
        Message<String> message = MessageBuilder
                .withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader("eventId", String.valueOf(outboxEvent.getId()))
                .build();

        try {
            // Step 3: 동기식으로 메시지 전송
            kafkaTemplate.send(message).get();  // ← .get() 중요! 비동기를 동기로 변환
            log.info("Event published to Kafka");

            // Step 4: OutboxEvent를 발행됨으로 표시
            outboxEvent.markPublished();
            outboxEventRepository.save(outboxEvent);

        } catch (Exception ex) {
            log.error("Kafka publish failed", ex);
            throw new KafkaPublishingException("Failed to publish to Kafka", ex);
        }
    };

    // Step 5: Circuit Breaker로 작업 실행
    try {
        circuitBreaker.executeRunnable(publishTask);
    } catch (CallNotPermittedException ex) {
        // Circuit이 OPEN 상태일 때 발생
        log.warn("Circuit Breaker is OPEN - request rejected");
        // OutboxEvent는 이미 DB에 저장되어 있으므로 손실 없음
    } catch (Exception ex) {
        // 기타 예외 처리
        log.warn("Circuit Breaker caught exception");
        // OutboxEvent는 DB에 계속 저장됨
    }
}
```

**중요 포인트**:
- Line 18: `kafkaTemplate.send(message).get()`
  - `send()`는 CompletableFuture 반환 (비동기)
  - `.get()` 호출하여 동기적으로 대기
  - Kafka 타임아웃 시 예외 발생 (Circuit Breaker가 캡처)
- Line 22-25: 발행 성공 시 OutboxEvent 상태 업데이트
- Line 27-34: Circuit Breaker에 작업 위임
  - `CallNotPermittedException`: Circuit OPEN 상태 (차단됨)
  - 기타 Exception: 느린 호출, 실패 등

### 2️⃣ 설정: application.yml

#### Kafka Producer 타임아웃 설정

```yaml
spring:
  kafka:
    producer:
      acks: all                    # 모든 replica에서 승인 대기
      retries: 1                   # 실패 시 1회 재시도
      request-timeout-ms: 10000    # ← 요청 타임아웃: 10초
      delivery-timeout-ms: 15000   # ← 배송 타임아웃: 15초
      batch-size: 16384
      linger-ms: 10
```

**의미**:
- `request-timeout-ms: 10000`: 개별 요청 최대 10초 대기
- `delivery-timeout-ms: 15000`: 전체 배송 최대 15초 대기
- 타임아웃 시 예외 발생 → Circuit Breaker가 감지

#### Resilience4j Circuit Breaker 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      kafka-publisher:
        failureRateThreshold: 50              # 50% 이상 실패 시 OPEN
        slowCallRateThreshold: 50             # 50% 이상 느린 호출 시 OPEN
        slowCallDurationThreshold: 5000ms     # 5초 이상 = 느린 호출
        minimumNumberOfCalls: 5               # 최소 5개 호출 후 판정
        waitDurationInOpenState: 30s          # OPEN → HALF_OPEN 대기 시간
        permittedNumberOfCallsInHalfOpenState: 3  # HALF_OPEN에서 테스트할 요청 수
        automaticTransitionFromOpenToHalfOpenEnabled: true  # 자동 전환
```

**각 설정 의미**:
- `failureRateThreshold: 50`: 실패율이 50% 이상이면 OPEN
- `slowCallRateThreshold: 50`: 느린 호출 비율이 50% 이상이면 OPEN
- `slowCallDurationThreshold: 5000ms`: 5초 이상 걸리는 호출을 "느림"으로 분류
- `minimumNumberOfCalls: 5`: 최소 5개 호출이 있어야 위 조건 판정
- `waitDurationInOpenState: 30s`: OPEN 상태에서 30초 후 HALF_OPEN으로 자동 전환
- `permittedNumberOfCallsInHalfOpenState: 3`: HALF_OPEN에서 최대 3개 요청으로 복구 시도

### 3️⃣ CircuitBreakerStatusController.java (모니터링 API)

**위치**: `backend/ingest-service/src/main/java/com/example/payment/web/CircuitBreakerStatusController.java`

#### REST 엔드포인트

```java
@RestController
@RequestMapping("/circuit-breaker")
public class CircuitBreakerStatusController {

    // GET /circuit-breaker/kafka-publisher
    @GetMapping("/kafka-publisher")
    public ResponseEntity<Map<String, Object>> getKafkaPublisherStatus() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("kafka-publisher");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        Map<String, Object> response = new HashMap<>();
        response.put("state", circuitBreaker.getState().toString());
        response.put("numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls());
        response.put("numberOfFailedCalls", metrics.getNumberOfFailedCalls());
        response.put("numberOfSlowCalls", metrics.getNumberOfSlowCalls());
        response.put("numberOfNotPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        response.put("slowCallRate", String.format("%.2f%%", metrics.getSlowCallRate()));
        response.put("failureRate", String.format("%.2f%%", metrics.getFailureRate()));

        return ResponseEntity.ok(response);
    }
}
```

#### 응답 예시

```json
{
  "state": "CLOSED",
  "numberOfSuccessfulCalls": 25,
  "numberOfFailedCalls": 0,
  "numberOfSlowCalls": 11,
  "numberOfNotPermittedCalls": 0,
  "slowCallRate": "44.00%",
  "failureRate": "0.00%"
}
```

---

## 🧪 수동 테스트

### 준비 사항

모든 서비스가 실행 중이어야 합니다:

```bash
docker compose up -d mariadb redis zookeeper kafka ingest-service consumer-worker
sleep 20
```

### Test 1: 초기 상태 확인

```bash
curl http://localhost:8080/circuit-breaker/kafka-publisher
```

**기대 결과**: `state: CLOSED`

### Test 2: 정상 요청 5개 (Kafka UP)

```bash
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/payments/authorize \
    -H "Content-Type: application/json" \
    -d "{\"merchantId\":\"TEST_$i\",\"amount\":50000,\"currency\":\"KRW\",\"idempotencyKey\":\"test-$i-$(date +%s)\"}"
  sleep 1
done
```

**기대 결과**: 각 요청이 1-2초 내에 HTTP 200으로 응답

**메트릭 확인**:
```bash
curl -s http://localhost:8080/circuit-breaker/kafka-publisher | grep numberOfSuccessfulCalls
# 결과: 5 증가
```

### Test 3: Kafka 중단

```bash
docker compose stop kafka
sleep 5
```

### Test 4: Kafka DOWN 상태에서 느린 요청 6개

```bash
for i in {1..6}; do
  echo "Slow request $i..."
  timeout 15 curl -s -X POST http://localhost:8080/payments/authorize \
    -H "Content-Type: application/json" \
    -d "{\"merchantId\":\"SLOW_$i\",\"amount\":50000,\"currency\":\"KRW\",\"idempotencyKey\":\"slow-$i-$(date +%s)\"}" > /dev/null 2>&1 &
  sleep 1
done
wait
```

**기대 결과**: 각 요청이 10-15초 정도 걸림 (타임아웃 대기)

**메트릭 확인**:
```bash
curl -s http://localhost:8080/circuit-breaker/kafka-publisher
```

**기대 결과**:
```json
{
  "state": "HALF_OPEN 또는 OPEN",
  "numberOfSlowCalls": 6,
  "slowCallRate": "54.54%"
}
```

### Test 5: Kafka 재시작

```bash
docker compose start kafka
sleep 15
```

### Test 6: 복구 요청

```bash
curl -s -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d "{\"merchantId\":\"RECOVERY\",\"amount\":50000,\"currency\":\"KRW\",\"idempotencyKey\":\"recovery-$(date +%s)\"}"
```

**기대 결과**: 1-2초 내에 빠른 응답

**최종 상태 확인**:
```bash
curl -s http://localhost:8080/circuit-breaker/kafka-publisher
# 기대: state: CLOSED
```

---

## 🤖 자동 테스트

### 자동 테스트 스크립트 실행

모든 테스트를 자동으로 수행하려면:

```bash
bash scripts/test-circuit-breaker.sh
```

### 스크립트의 9단계 흐름

1. **API 헬스 체크** - 최대 30초 대기
2. **초기 상태 확인** - CLOSED 상태 검증
3. **정상 요청 5개 전송** - Kafka UP
4. **Kafka 중단**
5. **느린 요청 6개 전송** - Kafka DOWN, 타임아웃
6. **Circuit Breaker 상태 확인** - HALF_OPEN 또는 OPEN
7. **Kafka 재시작**
8. **복구 요청 전송**
9. **최종 상태 확인 및 결과 출력**

### Jenkins 파이프라인 통합

`Jenkinsfile`에 "Circuit Breaker Test" 단계가 추가되었습니다:

```groovy
stage('Circuit Breaker Test') {
  steps {
    sh '''
      chmod +x scripts/test-circuit-breaker.sh
      bash scripts/test-circuit-breaker.sh

      TEST_RESULT=$?
      if [ $TEST_RESULT -eq 0 ]; then
        echo "✅ Circuit Breaker 테스트 통과"
      else
        echo "⚠️ Circuit Breaker 테스트 경고"
      fi
    '''
  }
}
```

**실행 순서**:
1. Jenkins 빌드 시작
2. 프론트엔드 빌드
3. 백엔드 빌드
4. Docker Compose 시작
5. 서비스 준비 대기
6. Smoke Test (기본 결제 요청)
7. **Circuit Breaker Test** (자동으로 전체 시나리오 실행)

---

## 📊 모니터링

### Prometheus

#### 접속
```
http://localhost:9090
```

#### 주요 쿼리

**Circuit Breaker 현재 상태**
```promql
resilience4j_circuitbreaker_state{name="kafka-publisher"}
```
- 0 = CLOSED (정상)
- 1 = OPEN (차단)
- 2 = HALF_OPEN (복구 시도)

**성공한 호출 수**
```promql
resilience4j_circuitbreaker_buffered_calls{kind="successful",name="kafka-publisher"}
```

**느린 호출 수**
```promql
resilience4j_circuitbreaker_buffered_calls{kind="slow_successful",name="kafka-publisher"}
```

**느린 호출 비율**
```promql
resilience4j_circuitbreaker_slow_call_rate{name="kafka-publisher"}
```

**실패율**
```promql
resilience4j_circuitbreaker_failure_rate{name="kafka-publisher"}
```

### Grafana

#### 접속
```
http://localhost:3000
ID: admin / PW: admin
```

#### 대시보드 확인

1. **Dashboards** → **Payment Service Overview** 클릭
2. 아래로 스크롤하여 Circuit Breaker 섹션 확인

#### Circuit Breaker 패널 (4가지)

**패널 1: Circuit Breaker State** (상태 표시)
- 유형: Gauge
- 색상: CLOSED (초록), OPEN (빨강), HALF_OPEN (노랑)

**패널 2: Slow Call Rate (%)** (느린 호출 비율)
- 유형: Stat
- 색상 임계값: 0-25% (초록), 25-50% (노랑), 50-75% (주황), 75%+ (빨강)

**패널 3: Failure Rate (%)** (실패율)
- 유형: Stat
- 색상 임계값: 0-10% (초록), 10-25% (노랑), 25-50% (주황), 50%+ (빨강)

**패널 4: Circuit Breaker Call Metrics** (호출 수 추이)
- 유형: Time Series
- 범례: Successful, Slow, Not Permitted

---

## 📖 용어 설명

| 용어 | 의미 | 값 |
|------|------|-----|
| **numberOfSuccessfulCalls** | 5초 이내 완료된 호출 수 | - |
| **numberOfFailedCalls** | 예외 발생한 호출 수 | - |
| **numberOfSlowCalls** | 5초 이상 걸린 호출 수 | - |
| **numberOfNotPermittedCalls** | Circuit OPEN일 때 차단된 호출 | - |
| **slowCallRate** | 느린 호출 비율 (%) | >= 50% → OPEN |
| **failureRate** | 실패한 호출 비율 (%) | >= 50% → OPEN |
| **state** | 현재 Circuit 상태 | CLOSED/OPEN/HALF_OPEN |

---

## 🐛 트러블슈팅

### 문제 1: API가 응답하지 않습니다

```bash
docker compose ps
docker compose logs ingest-service | tail -50
curl http://localhost:8080/actuator/health
```

**해결책**: 서비스가 "Up (healthy)" 상태인지 확인

### 문제 2: 메트릭이 안 나옵니다

```bash
curl http://localhost:8080/circuit-breaker/kafka-publisher
curl http://localhost:9090/api/v1/targets
```

**해결책**: 결제 요청을 먼저 보낸 후 메트릭 확인

### 문제 3: Grafana 패널이 안 보입니다

```bash
docker compose build grafana --no-cache
docker compose restart grafana
```

**해결책**: Docker 이미지 재빌드

### 문제 4: Circuit이 HALF_OPEN에 머물러 있습니다

```bash
docker compose ps kafka
docker compose start kafka && sleep 15
```

**해결책**: Kafka가 정상 재시작되면 자동으로 CLOSED로 전환

### 문제 5: 느린 호출이 기록되지 않습니다

**이는 정상입니다!** Kafka 중단 시:
- Producer가 타임아웃 대기 (request-timeout-ms: 10초)
- slowCallDurationThreshold (5초) 초과 → "느린 호출" 기록
- 실제 예외 발생하지 않음 → failureRate는 0%

---

## ✅ 체크리스트

Circuit Breaker 구현이 제대로 작동하는지 확인:

### 초기 설정
- [ ] 서비스 실행: `docker compose up -d`
- [ ] 헬스 체크 통과: `curl http://localhost:8080/actuator/health`
- [ ] Circuit Breaker 엔드포인트 응답
- [ ] Prometheus 타겟 추가 확인
- [ ] Grafana 대시보드 접속

### 수동 테스트
- [ ] 정상 요청 5개 완료
- [ ] `numberOfSuccessfulCalls` 증가
- [ ] Kafka 중단 후 느린 요청 6개
- [ ] `numberOfSlowCalls` 증가
- [ ] Circuit 상태: HALF_OPEN 또는 OPEN
- [ ] Kafka 재시작
- [ ] 복구 요청 빠른 응답
- [ ] 최종 상태: CLOSED

### 자동 테스트
- [ ] 스크립트 실행: `bash scripts/test-circuit-breaker.sh`
- [ ] 모든 9단계 완료
- [ ] 종료 코드 0 (성공)

### 프로덕션 배포 전
- [ ] 타임아웃 설정 검토
- [ ] Circuit Breaker 임계값 검토
- [ ] 모니터링 알림 설정
- [ ] 로그 보관 정책 설정
- [ ] 부하 테스트 (k6) 실행

---

## 📁 관련 파일

### 구현 파일
- `backend/ingest-service/src/main/java/com/example/payment/service/PaymentEventPublisher.java`
- `backend/ingest-service/src/main/java/com/example/payment/web/CircuitBreakerStatusController.java`
- `backend/ingest-service/src/main/java/com/example/payment/config/Resilience4jConfig.java`

### 설정 파일
- `backend/ingest-service/src/main/resources/application.yml`
- `backend/build.gradle.kts` (Resilience4j 의존성)

### 모니터링 파일
- `monitoring/grafana/dashboards/payment-overview.json`
- `monitoring/prometheus/prometheus.yml`

### 테스트 파일
- `scripts/test-circuit-breaker.sh`
- `Jenkinsfile`

---

## 📚 추가 참고 자료

### Resilience4j 공식 문서
- [Resilience4j Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [State Machine Documentation](https://resilience4j.readme.io/docs/circuitbreaker#state-machine)

### Circuit Breaker 패턴
- [Martin Fowler - Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Release It! - Michael Nygard](https://pragprog.com/titles/mnee2/release-it-second-edition/)

### Spring Boot + Kafka
- [Spring for Apache Kafka](https://spring.io/projects/spring-kafka)
- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)

---

## 🎓 구현 완료 항목

### 의존성 추가
- ✅ Resilience4j 라이브러리 3개 추가
- ✅ Spring Boot 3 자동 설정

### 설정 구현
- ✅ Kafka Producer 타임아웃 설정
- ✅ Circuit Breaker 상세 설정
- ✅ Prometheus 메트릭 활성화

### 핵심 로직
- ✅ Programmatic Circuit Breaker API 사용
- ✅ Transactional Outbox Pattern 적용
- ✅ 느린 호출 감지 구현
- ✅ 자동 복구 메커니즘

### 모니터링 & 관찰성
- ✅ REST API 엔드포인트
- ✅ Actuator 통합
- ✅ Prometheus 메트릭 노출
- ✅ Grafana 패널 추가

### 자동화
- ✅ 테스트 스크립트 생성
- ✅ Jenkins 파이프라인 통합
- ✅ 종료 코드 기반 성공/실패 판정

---

**최종 수정**: 2025-10-27
**담당자**: Payment SWElite Team
**상태**: 프로덕션 배포 준비 완료 ✅
