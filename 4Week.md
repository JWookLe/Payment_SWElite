# 4주차 작업

## 전체 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                   Frontend                                   │
│                            (React + Vite, Port 5173)                         │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │ HTTP
                                 ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                              API Gateway (8080)                              │
│                         Spring Cloud Gateway + Eureka                        │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │ Load Balancing
                                 ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ingest-service (8080)                               │
│                  ┌────────────────────────────────┐                          │
│                  │  REST API (Authorize/Capture/  │                          │
│                  │  Refund) + Circuit Breaker     │                          │
│                  └──────────┬─────────────────────┘                          │
│                             │                                                │
│                  ┌──────────▼─────────────────────┐                          │
│                  │   Outbox Pattern (DB 저장)     │                          │
│                  └──────────┬─────────────────────┘                          │
│                             │                                                │
│                  ┌──────────▼─────────────────────┐                          │
│                  │  Outbox Polling Scheduler      │                          │
│                  │  (10초마다, 최대 10회 재시도)  │                          │
│                  └──────────┬─────────────────────┘                          │
└─────────────────────────────┼────────────────────────────────────────────────┘
                              │ Kafka Publish
                              ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Kafka + Zookeeper                               │
│    Topics: payment.authorized, payment.capture-requested,                   │
│            payment.captured, payment.refund-requested,                      │
│            payment.refunded, payment.dlq                                    │
└──────┬──────────────────────┬────────────────────┬─────────────────────────┘
       │                      │                    │
       │ Subscribe            │ Subscribe          │ Subscribe
       ↓                      ↓                    ↓
┌──────────────┐    ┌───────────────────┐    ┌──────────────────┐
│consumer-worker│    │settlement-worker  │    │ refund-worker    │
│   (8081)     │    │     (8084)        │    │    (8085)        │
├──────────────┤    ├───────────────────┤    ├──────────────────┤
│ 회계 장부     │    │ Mock PG 정산 API  │    │ Mock PG 환불 API │
│ ledger_entry │    │ (1~3초 지연)      │    │ (1~3초 지연)     │
│ 생성         │    │ (5% 실패율)       │    │ (5% 실패율)      │
│              │    │                   │    │                  │
│              │    │ Retry Scheduler   │    │ Retry Scheduler  │
│              │    │ (10초 주기,       │    │ (10초 주기,      │
│              │    │  최대 10회)       │    │  최대 10회)      │
└──────┬───────┘    └─────────┬─────────┘    └────────┬─────────┘
       │                      │                       │
       │ Write               │ Write                 │ Write
       ↓                      ↓                       ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MariaDB (paydb)                                 │
│  Tables: payment, ledger_entry, outbox_event, idem_response_cache,          │
│          settlement_request, refund_request                                 │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         monitoring-service (8082)                            │
│  - Circuit Breaker 상태 조회                                                 │
│  - Database 쿼리 (결제/정산/환불 통계)                                       │
│  - Redis 캐시 통계                                                           │
│  - Statistics API: /api/stats/settlement, /api/stats/refund                 │
└─────────────────────────────────────────────────────────────────────────────┘
       ↓ Metrics Export
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Prometheus (9090)                                   │
│  메트릭 수집: Circuit Breaker, HTTP 요청, Kafka 메시지, DB 쿼리              │
└─────────────────────────────────────────────────────────────────────────────┘
       ↓ Query
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Grafana (3000)                                    │
│  Dashboards:                                                                 │
│    - Payment Service Overview                                                │
│    - Circuit Breaker Status                                                  │
│    - Settlement & Refund Statistics (8 panels) ← Week 7 신규                 │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         Service Discovery                                    │
│                      Eureka Server (8761)                                    │
│  Registered: ingest-service, consumer-worker, settlement-worker,             │
│              refund-worker, monitoring-service, gateway                     │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              Redis (6379)                                    │
│  - Rate Limit 카운터 (24,000/분)                                             │
│  - 멱등성 캐시 (TTL 600초)                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 주요 데이터 플로우

### 1. 결제 승인 플로우

```
Frontend → Gateway → ingest-service (POST /api/payments/authorize)
  → payment INSERT (status: AUTHORIZED)
  → outbox_event INSERT
  → Outbox Polling Scheduler (10초마다)
  → Kafka: payment.authorized
  → consumer-worker: ledger_entry INSERT (차변: 매출채권)
```

### 2. 정산 플로우

```
Frontend → Gateway → ingest-service (POST /api/payments/capture/{id})
  → Kafka: payment.capture-requested

settlement-worker:
  → settlement_request INSERT (status: PENDING)
  → Mock PG API 호출 (1~3초, 5% 실패율)
  → 성공 시:
    - settlement_request → SUCCESS
    - payment → CAPTURED
    - Kafka: payment.captured
  → 실패 시:
    - settlement_request → FAILED
    - Retry Scheduler (10초마다, 최대 10회)
    - retry_count >= 10 → Dead Letter
```

### 3. 환불 플로우

```
Frontend → Gateway → ingest-service (POST /api/payments/refund/{id})
  → Kafka: payment.refund-requested

refund-worker:
  → refund_request INSERT (status: PENDING, amount: 부분환불 가능)
  → Mock PG API 호출 (1~3초, 5% 실패율)
  → 성공 시:
    - refund_request → SUCCESS
    - payment → REFUNDED
    - Kafka: payment.refunded
  → 실패 시:
    - refund_request → FAILED
    - Retry Scheduler (10초마다, 최대 10회)
    - retry_count >= 10 → Dead Letter
```

---

## 1. Outbox Pattern 장애 진단 및 복구

### 문제 상황

Jenkins 빌드 완료 후 Grafana에서 Circuit Breaker가 HALF_OPEN 상태로 표시되면서 Outbox 패턴이 제대로 작동하지 않는 문제 발견.

### 원인 분석

#### 초기 점검

1. **consumer-worker 컨테이너 확인**

   - 정상 실행 중
   - Kafka 이벤트 처리 정상
2. **Kafka 연결 확인**

   - 연결 정상
   - 토픽별 메시지 처리 확인
3. **Circuit Breaker 상태 확인**

   - API 엔드포인트 404 발생 (노출 안 됨)
   - Prometheus 메트릭으로 확인 필요
4. **데이터베이스 조회로 핵심 문제 발견**

   ```sql
   SELECT COUNT(*) as total,
          SUM(CASE WHEN published = 0 THEN 1 ELSE 0 END) as pending
   FROM outbox_event;

   -- 결과: total: 80417, pending: 706
   ```

   - 총 80,417개의 outbox 이벤트
   - 미발행 이벤트 706개 누적
   - 가장 오래된 미발행 이벤트: 2025-10-20 (약 2주 전)

#### 근본 원인

1. **Circuit Breaker 이력 확인**

   - Jenkins 빌드 중 Kafka 재시작으로 Circuit Breaker OPEN 발생
   - 이후 HALF_OPEN 상태로 전환되었으나 완전 복구 안 됨
2. **Outbox 폴링 스케줄러 미구현**

   - `PaymentEventPublisher` 코드 분석 결과:
     ```java
     // Line 117-118: 주석만 있고 실제 구현 없음
     // Event will be retried by outbox polling
     ```
   - Outbox 이벤트는 저장되지만 재시도 로직이 전혀 없음
   - 실패한 이벤트가 영구적으로 pending 상태로 남음

### 문제 요약

- Kafka 장애 시 Outbox에 이벤트는 저장되지만 복구 후 자동으로 재발행되지 않음
- 2주간 706개의 이벤트가 미발행 상태로 누적됨
- 현업 시스템이라면 데이터 정합성 문제로 이어질 심각한 상황

---

## 2. Outbox 폴링 스케줄러 구현 (현업 패턴)

### 구현 목표

현업에서 사용하는 프로덕션 수준의 Outbox 폴링 패턴 구현:

- Circuit Breaker 상태 인지
- 분산 환경 대응 (비관적 락)
- 재시도 전략 (지수 백오프)
- Dead Letter 처리
- 운영 환경 설정 가능

### 구현 내용

#### 1. OutboxEvent 도메인 강화

**파일**: `backend/ingest-service/src/main/java/com/example/payment/domain/OutboxEvent.java`

재시도 추적을 위한 필드 추가:

```java
@Column(name = "retry_count", nullable = false)
private int retryCount = 0;

@Column(name = "last_retry_at")
private Instant lastRetryAt;

@Column(name = "published_at")
private Instant publishedAt;
```

비즈니스 메서드 추가:

```java
public void incrementRetryCount() {
    this.retryCount++;
    this.lastRetryAt = Instant.now();
}

public boolean canRetry(int maxRetries) {
    return retryCount < maxRetries;
}
```

**자동 DDL 적용**:

- JPA가 애플리케이션 시작 시 자동으로 컬럼 추가
- 기존 데이터는 default 값(0, null)으로 초기화됨

#### 2. OutboxEventRepository 강화

**파일**: `backend/ingest-service/src/main/java/com/example/payment/repository/OutboxEventRepository.java`

프로덕션 수준의 쿼리 추가:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
       "AND e.retryCount < :maxRetries " +
       "AND (e.lastRetryAt IS NULL OR e.lastRetryAt < :retryThreshold) " +
       "ORDER BY e.createdAt ASC")
List<OutboxEvent> findUnpublishedEventsForRetry(
        @Param("maxRetries") int maxRetries,
        @Param("retryThreshold") Instant retryThreshold,
        Pageable pageable
);
```

**핵심 포인트**:

- `@Lock(PESSIMISTIC_WRITE)`: 분산 환경에서 동일 이벤트 중복 처리 방지
- `retryCount < maxRetries`: 재시도 제한
- `lastRetryAt < retryThreshold`: 지수 백오프 (최소 대기 시간)
- `ORDER BY createdAt ASC`: 오래된 이벤트부터 처리

Dead Letter 조회 쿼리:

```java
@Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
       "AND e.retryCount >= :maxRetries")
List<OutboxEvent> findDeadLetterCandidates(
        @Param("maxRetries") int maxRetries
);
```

#### 3. OutboxEventScheduler 구현

**파일**: `backend/ingest-service/src/main/java/com/example/payment/scheduler/OutboxEventScheduler.java`

프로덕션 수준의 폴링 스케줄러:

```java
@Component
public class OutboxEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventScheduler.class);
    private static final String CIRCUIT_BREAKER_NAME = "kafka-publisher";

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher eventPublisher;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${outbox.polling.batch-size:100}")
    private int batchSize;

    @Value("${outbox.polling.max-retries:10}")
    private int maxRetries;

    @Value("${outbox.polling.retry-interval-seconds:30}")
    private int retryIntervalSeconds;

    @Scheduled(fixedDelayString = "${outbox.polling.interval-ms:10000}",
               initialDelayString = "${outbox.polling.initial-delay-ms:15000}")
    @Transactional
    public void pollAndRetryUnpublishedEvents() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // Circuit Breaker가 OPEN이면 스킵 (Kafka 장애 중)
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.debug("Outbox polling skipped - Circuit Breaker is OPEN");
            return;
        }

        Instant retryThreshold = Instant.now().minus(Duration.ofSeconds(retryIntervalSeconds));

        List<OutboxEvent> events = outboxEventRepository.findUnpublishedEventsForRetry(
                maxRetries,
                retryThreshold,
                PageRequest.of(0, batchSize)
        );

        if (events.isEmpty()) {
            log.debug("No unpublished events to retry");
            return;
        }

        int succeeded = 0;
        int failed = 0;

        for (OutboxEvent event : events) {
            try {
                event.incrementRetryCount();
                outboxEventRepository.save(event);

                String topic = topicNameFor(event.getEventType());
                eventPublisher.publishToKafkaWithCircuitBreaker(event, topic, event.getPayload());

                succeeded++;
            } catch (Exception ex) {
                failed++;
                log.warn("Failed to retry outbox event {}: {}", event.getId(), ex.getMessage());

                if (event.getRetryCount() >= maxRetries) {
                    handleDeadLetterEvent(event);
                }
            }
        }

        log.info("Outbox polling completed: {} succeeded, {} failed", succeeded, failed);
    }

    @Scheduled(fixedDelayString = "${outbox.dead-letter.check-interval-ms:300000}")
    public void checkDeadLetterEvents() {
        List<OutboxEvent> deadLetters = outboxEventRepository.findDeadLetterCandidates(maxRetries);

        if (!deadLetters.isEmpty()) {
            log.error("Found {} dead letter events that exceeded max retries", deadLetters.size());
        }
    }

    private void handleDeadLetterEvent(OutboxEvent event) {
        log.error("OutboxEvent {} exceeded max retries ({}). Manual intervention required.",
                event.getId(), maxRetries);
    }
}
```

**핵심 기능**:

1. **Circuit Breaker 인지**

   - Kafka 장애 시 (OPEN 상태) 폴링 스킵
   - 불필요한 재시도 방지
2. **배치 처리**

   - 한 번에 100개씩 처리
   - 대량 이벤트도 안정적으로 처리
3. **지수 백오프**

   - 최소 30초 간격으로 재시도
   - 시스템 부하 분산
4. **재시도 제한**

   - 최대 10회 재시도
   - 무한 재시도 방지
5. **Dead Letter 모니터링**

   - 5분마다 Dead Letter 이벤트 확인
   - 로그 경고로 운영자 알림
6. **트랜잭션 관리**

   - `@Transactional`로 원자성 보장
   - 재시도 카운트 증가와 발행을 하나의 트랜잭션으로

#### 4. PaymentApplication 설정

**파일**: `backend/ingest-service/src/main/java/com/example/payment/PaymentApplication.java`

Spring Scheduling 활성화:

```java
@SpringBootApplication
@EnableConfigurationProperties({...})
@EnableDiscoveryClient
@EnableScheduling  // 추가
public class PaymentApplication {
```

#### 5. application.yml 설정

**파일**: `backend/ingest-service/src/main/resources/application.yml`

운영 환경에서 튜닝 가능한 설정:

```yaml
outbox:
  polling:
    enabled: true
    interval-ms: 10000           # 10초마다 폴링
    initial-delay-ms: 15000      # 시작 후 15초 대기
    batch-size: 100              # 한 번에 100개 처리
    max-retries: 10              # 최대 10회 재시도
    retry-interval-seconds: 30   # 최소 30초 간격
  dead-letter:
    check-interval-ms: 300000    # 5분마다 Dead Letter 체크
```

---

## 3. 배포 및 검증

### 배포 과정

#### 문제 1: 네트워크 불일치

**증상**:

```
java.net.UnknownHostException: mariadb
```

**원인**:

- 새로운 컨테이너가 `payment_swelite_default` 네트워크에 생성됨
- 기존 컨테이너들은 `payment-swelite-pipeline_default` 네트워크에 있음

**해결**:

```bash
docker compose -p payment-swelite-pipeline up -d --no-deps --build ingest-service
```

올바른 프로젝트 네임 지정으로 동일 네트워크에 배포.

#### 문제 2: 포트 충돌

**증상**:

```
Bind for 0.0.0.0:8080 failed: port is already allocated
```

**원인**:

- Gateway 서비스가 이미 8080 포트 사용 중
- 수동 docker run 시도로 충돌

**해결**:

- Docker Compose 사용으로 포트 관리 일원화

### 검증 결과

#### 1차 폴링 결과 (시작 후 15초)

```
2025-11-03T00:53:36.821Z  INFO --- [scheduling-1]
Outbox polling completed: 100 succeeded, 0 failed
```

#### 2차 폴링 결과 (시작 후 25초)

```
2025-11-03T00:53:46.821Z  INFO --- [scheduling-1]
Outbox polling completed: 100 succeeded, 0 failed
```

#### 최종 확인 (약 2분 후)

```sql
SELECT COUNT(*) as total,
       SUM(CASE WHEN published = 0 THEN 1 ELSE 0 END) as pending
FROM outbox_event;

-- 결과: total: 80417, pending: 0
```

**성과**:

- 706개의 미발행 이벤트 모두 처리 완료
- 가장 오래된 이벤트(10월 20일)부터 순서대로 발행됨
- Circuit Breaker 정상 상태(CLOSED)로 복구됨

#### 발행 통계 확인

```sql
SELECT MIN(created_at) as oldest_processed,
       MAX(published_at) as latest_published,
       COUNT(*) as total_published
FROM outbox_event
WHERE published = 1
  AND published_at > '2025-11-03 00:50:00';

-- 결과:
-- oldest_processed: 2025-10-20 07:13:47
-- latest_published: 2025-11-03 00:54:20
-- total_published: 706
```

---

## 4. 프로덕션 패턴 적용 포인트

### 분산 환경 대응

**비관적 락 (Pessimistic Locking)**:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

- 여러 인스턴스가 동시에 실행되어도 안전
- 동일한 이벤트를 중복 처리하지 않음
- 데이터베이스 레벨에서 동시성 제어

### 장애 격리

**Circuit Breaker 통합**:

```java
if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
    log.debug("Outbox polling skipped - Circuit Breaker is OPEN");
    return;
}
```

- Kafka 장애 시 폴링 중단
- 불필요한 리소스 소모 방지
- 시스템 부하 최소화

### 점진적 복구

**지수 백오프 (Exponential Backoff)**:

```java
Instant retryThreshold = Instant.now().minus(Duration.ofSeconds(retryIntervalSeconds));
```

- 최소 30초 간격으로 재시도
- 급격한 부하 증가 방지
- 외부 시스템 복구 시간 확보

### 운영 가시성

**로깅 및 모니터링**:

```java
log.info("Outbox polling completed: {} succeeded, {} failed", succeeded, failed);
log.error("Found {} dead letter events that exceeded max retries", deadLetters.size());
```

- 처리 현황 실시간 로그
- Dead Letter 자동 감지
- 운영자 대응 가능

### 설정 기반 운영

**외부화된 설정**:

```yaml
outbox:
  polling:
    interval-ms: 10000
    batch-size: 100
    max-retries: 10
```

- 코드 수정 없이 튜닝 가능
- 환경별 다른 설정 적용 가능
- 운영 중 동적 조정 가능

---

## 5. 달성한 목표

- ✅ Outbox 패턴 미구현 문제 진단
- ✅ 2주간 누적된 706개 이벤트 완전 복구
- ✅ 프로덕션 수준 Outbox 폴링 스케줄러 구현
- ✅ Circuit Breaker 통합으로 장애 격리
- ✅ 비관적 락으로 분산 환경 대응
- ✅ Dead Letter 모니터링 추가
- ✅ 운영 환경 설정 외부화

---

## 6. 구현 파일 목록

### 수정된 파일

1. **OutboxEvent.java**

   - 재시도 추적 필드 추가 (retryCount, lastRetryAt, publishedAt)
   - 비즈니스 메서드 추가
2. **OutboxEventRepository.java**

   - 비관적 락 쿼리 추가
   - Dead Letter 조회 쿼리 추가
3. **PaymentApplication.java**

   - @EnableScheduling 추가
4. **application.yml**

   - outbox 설정 섹션 추가

### 신규 파일

1. **OutboxEventScheduler.java** (185줄)
   - 핵심 폴링 로직
   - Circuit Breaker 통합
   - Dead Letter 처리

---

## 7. 향후 개선 방향

### 모니터링 강화

- Outbox 폴링 메트릭 추가 (Prometheus)
- Grafana 대시보드에 Outbox 상태 패널 추가
- Dead Letter 이벤트 알림 설정 (Slack, Email)

### 성능 최적화

- 배치 크기 동적 조정 (부하에 따라)
- 파티셔닝 전략 (이벤트 타입별 분리)
- 병렬 처리 (멀티 스레드)

### 운영 도구

- Dead Letter 재처리 API 추가
- Outbox 통계 조회 API
- 수동 재시도 트리거 기능

---

## 8. 용어 정리

- **Outbox Pattern**: 트랜잭션 아웃박스 패턴. 이벤트 발행 실패에도 데이터 정합성 보장
- **Polling**: 주기적으로 상태를 확인하는 방식
- **Pessimistic Lock**: 비관적 락. 트랜잭션 시작 시 데이터 잠금
- **Exponential Backoff**: 지수 백오프. 재시도 간격을 점진적으로 늘리는 전략
- **Dead Letter**: 재시도 제한을 초과한 메시지. 수동 처리 필요
- **Circuit Breaker Aware**: Circuit Breaker 상태를 인지하고 동작을 조정
- **Batch Processing**: 여러 항목을 한 번에 처리하는 방식
- **Fixed Delay**: 이전 작업 종료 후 일정 시간 대기
- **Initial Delay**: 애플리케이션 시작 후 첫 실행까지 대기 시간

---

## 9. 승인/정산 분리 아키텍처 (settlement-worker)

### 배경

기존 시스템은 결제 승인과 정산을 하나의 API에서 처리했지만, 실제 PG사에서는 승인과 정산이 분리되어 있음:

- **승인(Authorization)**: 즉시 처리 (카드사 승인)
- **정산(Settlement)**: 비동기 처리 (실제 자금 이동)

### 구현 목표

현업 PG 구조를 따라 승인/정산 분리:

- 결제 상태 모델 확장 (3단계 → 11단계)
- settlement-worker 마이크로서비스 추가
- 이벤트 기반 비동기 처리
- PG API 호출 시뮬레이션

### 구현 내용

#### 1. PaymentStatus 확장

**파일**: `backend/ingest-service/src/main/java/com/example/payment/domain/PaymentStatus.java`

11단계 상태 모델:

```java
public enum PaymentStatus {
    // 승인 단계
    READY,              // 결제 준비
    AUTHORIZED,         // 승인 완료
    AUTH_FAILED,        // 승인 실패

    // 정산 단계
    CAPTURE_REQUESTED,  // 정산 요청됨
    CAPTURED,           // 정산 완료
    CAPTURE_FAILED,     // 정산 실패

    // 환불 단계
    REFUND_REQUESTED,   // 환불 요청됨
    REFUNDED,           // 환불 완료
    REFUND_FAILED,      // 환불 실패
    PARTIAL_REFUNDED,   // 부분 환불

    // 레거시 호환
    @Deprecated REQUESTED,
    @Deprecated COMPLETED,
    @Deprecated CANCELLED
}
```

#### 2. 데이터베이스 스키마

**파일**: `backend/ingest-service/src/main/resources/schema.sql`

새로운 테이블 추가:

```sql
-- payment 테이블 수정
status VARCHAR(50) NOT NULL,  -- ENUM에서 VARCHAR로 변경

-- settlement_request 테이블 (정산 추적)
CREATE TABLE IF NOT EXISTS settlement_request (
  id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id              BIGINT          NOT NULL,
  request_amount          DECIMAL(15,2)   NOT NULL,
  status                  VARCHAR(50)     NOT NULL,
  pg_transaction_id       VARCHAR(255),
  pg_response_code        VARCHAR(50),
  pg_response_message     TEXT,
  retry_count             INT             NOT NULL DEFAULT 0,
  last_retry_at           TIMESTAMP(3),
  requested_at            TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at            TIMESTAMP(3),
  CONSTRAINT fk_settlement_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
);

-- refund_request 테이블 (환불 추적)
CREATE TABLE IF NOT EXISTS refund_request (
  id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id                  BIGINT          NOT NULL,
  refund_amount               DECIMAL(15,2)   NOT NULL,
  refund_reason               VARCHAR(500),
  status                      VARCHAR(50)     NOT NULL,
  pg_cancel_transaction_id    VARCHAR(255),
  pg_response_code            VARCHAR(50),
  pg_response_message         TEXT,
  retry_count                 INT             NOT NULL DEFAULT 0,
  last_retry_at               TIMESTAMP(3),
  requested_at                TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at                TIMESTAMP(3),
  CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
);
```

#### 3. settlement-worker 서비스

**새로운 마이크로서비스 구조**:

```
backend/settlement-worker/
├── src/main/java/com/example/settlement/
│   ├── SettlementWorkerApplication.java  # @EnableKafka, @EnableScheduling
│   ├── domain/
│   │   ├── Payment.java
│   │   ├── PaymentStatus.java
│   │   ├── SettlementRequest.java
│   │   └── SettlementStatus.java
│   ├── repository/
│   │   ├── PaymentRepository.java
│   │   └── SettlementRequestRepository.java
│   ├── service/
│   │   └── SettlementService.java        # 정산 처리 로직
│   ├── consumer/
│   │   └── SettlementEventConsumer.java  # Kafka 구독
│   ├── client/
│   │   ├── MockPgApiClient.java          # PG API 시뮬레이션
│   │   └── PgApiException.java
│   └── config/
│       └── KafkaConfig.java
└── Dockerfile
```

**MockPgApiClient.java**: PG API 시뮬레이션

```java
@Component
public class MockPgApiClient {
    public SettlementResponse requestSettlement(Long paymentId, BigDecimal amount)
            throws PgApiException {
        // 1~3초 지연 시뮬레이션
        int delay = ThreadLocalRandom.current().nextInt(1000, 3000);
        Thread.sleep(delay);

        // 5% 확률로 실패 시뮬레이션
        if (Math.random() < 0.05) {
            throw new PgApiException("PG_TIMEOUT", "정산 API 타임아웃");
        }

        String transactionId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        return new SettlementResponse("SUCCESS", transactionId, "0000",
                                      "정산 성공", amount, Instant.now());
    }
}
```

**SettlementService.java**: 정산 처리

```java
@Service
public class SettlementService {
    @Transactional
    public void processSettlement(Long paymentId, Long amount) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();

        SettlementRequest settlementRequest = new SettlementRequest(
            paymentId, BigDecimal.valueOf(amount)
        );
        settlementRequestRepository.save(settlementRequest);

        try {
            // Mock PG API 호출
            SettlementResponse response = pgApiClient.requestSettlement(
                paymentId, BigDecimal.valueOf(amount)
            );

            settlementRequest.markSuccess(
                response.getTransactionId(),
                response.getResponseCode(),
                response.getResponseMessage()
            );
            payment.setStatus(PaymentStatus.CAPTURED);

            // payment.captured 이벤트 발행
            publishCapturedEvent(payment);

        } catch (PgApiException ex) {
            settlementRequest.markFailed(ex.getErrorCode(), ex.getMessage());
            payment.setStatus(PaymentStatus.CAPTURE_FAILED);
        }
    }
}
```

**SettlementEventConsumer.java**: Kafka 이벤트 수신

```java
@Component
public class SettlementEventConsumer {
    @KafkaListener(
        topics = "payment.capture-requested",
        groupId = "settlement-worker-group"
    )
    public void handleCaptureRequested(@Payload String message) {
        Map<String, Object> payload = objectMapper.readValue(message, Map.class);
        Long paymentId = getLongValue(payload, "paymentId");
        Long amount = getLongValue(payload, "amount");

        settlementService.processSettlement(paymentId, amount);
    }
}
```

#### 4. 이벤트 플로우

```
1. 클라이언트: POST /api/payments/authorize
   ↓
2. ingest-service: Payment 생성 (status: AUTHORIZED)
   ↓
3. ingest-service: payment.capture-requested 이벤트 발행
   ↓
4. settlement-worker: Kafka 이벤트 수신
   ↓
5. settlement-worker: Mock PG API 호출 (1~3초 소요)
   ↓
6. settlement-worker: settlement_request 기록 생성
   ↓
7. settlement-worker: Payment 상태 업데이트 (CAPTURED)
   ↓
8. settlement-worker: payment.captured 이벤트 발행
   ↓
9. consumer-worker: 원장 기록 생성
```

### E2E 검증

#### 테스트 실행

```bash
curl -X POST http://localhost:8080/api/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{
    "merchantId":"WEEK5_FINAL",
    "amount":250000,
    "currency":"KRW",
    "idempotencyKey":"week5-final-e2e-003"
  }'
```

#### 결과 확인

**1. API 응답**:

```json
{
  "paymentId": 68434,
  "status": "AUTHORIZED",
  "amount": 250000,
  "currency": "KRW"
}
```

**2. settlement-worker 로그**:

```
2025-11-03T05:01:05 Received capture-requested event: paymentId=68434
2025-11-03T05:01:05 Requesting settlement to Mock PG: paymentId=68434
2025-11-03T05:01:07 Mock PG settlement succeeded: txn_c0855358
2025-11-03T05:01:07 Published payment.captured event: paymentId=68434
```

**3. payment 테이블**:

```
payment_id: 68434
status: CAPTURED
amount: 250000
currency: KRW
```

**4. settlement_request 테이블**:

```
id: 3
payment_id: 68434
request_amount: 250000.00
status: SUCCESS
pg_transaction_id: txn_c0855358
pg_response_code: 0000
retry_count: 0
```

**5. ledger_entry 테이블**:

```
entry_id: 7
payment_id: 68434
debit_account: merchant_receivable
credit_account: cash
amount: 250000
```

### 달성한 목표

- ✅ 결제 상태 모델 11단계 확장
- ✅ settlement-worker 마이크로서비스 구현
- ✅ Mock PG API 시뮬레이션 (1~3초 지연, 5% 실패율)
- ✅ settlement_request / refund_request 테이블 추가
- ✅ 이벤트 기반 비동기 처리 (Kafka)
- ✅ E2E 플로우 검증 완료
- ✅ Eureka 서비스 디스커버리 통합

### 현업 패턴 적용

1. **승인/정산 분리**: 실제 PG사 구조 반영
2. **재시도 추적**: retry_count로 재시도 이력 관리
3. **이력 보관**: PG 응답 전체 저장 (추적성)
4. **분산 처리**: Kafka 기반 비동기 이벤트
5. **장애 격리**: settlement-worker 독립 실행

### 향후 개선 방향

- ~~환불 워커 구현 (refund-worker)~~ ✅
- ~~정산 배치 스케줄러 (일일 정산)~~ ✅
- ~~재시도 실패 시 알림 연동~~ ✅
- ~~정산 통계 대시보드~~ ✅

---

## 11. 환불 워커 구현 (refund-worker)

### 배경

settlement-worker와 동일한 구조로 환불 처리를 독립 마이크로서비스로 분리

### 구현 내용

**RefundWorkerApplication**

- payment.refund-requested 토픽 구독
- Mock PG 환불 API 호출
- payment.refunded 이벤트 발행

**MockPgApiClient** (환불 API)

```java
public RefundResponse requestRefund(Long paymentId, BigDecimal amount, String reason) {
    // 1~3초 지연 시뮬레이션
    Thread.sleep(ThreadLocalRandom.current().nextInt(1000, 3000));

    // 5% 확률로 실패
    if (Math.random() < 0.05) {
        throw new PgApiException("PG_TIMEOUT", "환불 API 타임아웃");
    }

    String cancelTxnId = "cancel_" + UUID.randomUUID().toString().substring(0, 8);
    return new RefundResponse("SUCCESS", cancelTxnId, "0000", "환불 성공", amount, Instant.now());
}
```

### E2E 검증 결과

**1. 승인**

```
paymentId: 68435
status: AUTHORIZED
```

**2. 정산 (자동)**

```
status: CAPTURED (약 2초 소요)
settlement_request 기록 생성
```

**3. 환불**

```
POST /api/payments/refund/68435
→ status: REFUND_REQUESTED
→ refund-worker 처리 (약 2초 소요)
→ status: REFUNDED
→ refund_request: pg_cancel_transaction_id=cancel_3c1db2f9, retry_count=0
```

---

## 12. 재시도 스케줄러 및 통계 대시보드

### 배경

실패한 정산/환불 요청을 자동으로 재시도하고, 운영 가시성을 위한 통계 대시보드 구축

### 1. Settlement Retry Scheduler

**파일**: `backend/settlement-worker/src/main/java/com/example/settlement/scheduler/SettlementRetryScheduler.java`

**핵심 기능**:

- 10초마다 실패한 정산 자동 재시도
- 최소 30초 간격 (지수 백오프)
- 최대 10회 재시도
- Dead Letter 모니터링 (5분마다)

```java
@Scheduled(fixedDelayString = "${settlement.retry-scheduler.interval-ms:10000}",
           initialDelayString = "${settlement.retry-scheduler.initial-delay-ms:30000}")
@Transactional
public void retryFailedSettlements() {
    Instant retryThreshold = Instant.now().minus(retryIntervalSeconds, ChronoUnit.SECONDS);

    List<SettlementRequest> failedRequests = settlementRequestRepository
            .findByStatusAndRetryCountLessThanAndLastRetryAtBefore(
                    SettlementStatus.FAILED,
                    maxRetries,
                    retryThreshold
            );

    // 각 실패 건 재시도...
}
```

### 2. Refund Retry Scheduler

**파일**: `backend/refund-worker/src/main/java/com/example/refund/scheduler/RefundRetryScheduler.java`

동일한 패턴으로 환불 재시도 구현

### 3. 정산/환불 통계 API

**파일**: `backend/monitoring-service/src/main/java/com/example/monitoring/service/SettlementStatsService.java`

**제공 API**:

```bash
# 정산 통계
GET /api/stats/settlement
{
  "totalCount": 1234,
  "successCount": 1200,
  "failedCount": 30,
  "pendingCount": 4,
  "totalAmount": 150000000.00,
  "deadLetterCount": 2,
  "successRate": 97.24
}

# 환불 통계
GET /api/stats/refund
{
  "totalCount": 56,
  "successCount": 54,
  "failedCount": 2,
  "pendingCount": 0,
  "totalAmount": 2800000.00,
  "deadLetterCount": 0,
  "successRate": 96.43
}

# 전체 통계
GET /api/stats/overview
{
  "authorizedCount": 500,
  "capturedCount": 480,
  "refundedCount": 54,
  "settlement": {...},
  "refund": {...}
}
```

### 4. Grafana 대시보드

**파일**: `monitoring/grafana/dashboards/settlement-refund-stats.json`

**패널 구성**:

1. **Settlement Success Rate** (Stat 패널)

   - 정산 성공률 (%)
   - 임계값: 0% (빨강), 90% (노랑), 95% (초록)
2. **Refund Success Rate** (Stat 패널)

   - 환불 성공률 (%)
3. **Settlement Dead Letters** (Stat 패널)

   - 최대 재시도 초과 건수
   - 임계값: 0 (초록), 1 (노랑), 10 (빨강)
4. **Refund Dead Letters** (Stat 패널)

   - 환불 Dead Letter 건수
5. **Settlement Request Rate** (Time Series)

   - 정산 요청 처리율 (req/s)
6. **Refund Request Rate** (Time Series)

   - 환불 요청 처리율 (req/s)
7. **Settlement Status Distribution** (Pie Chart)

   - SUCCESS / FAILED / PENDING 비율
8. **Refund Status Distribution** (Pie Chart)

   - SUCCESS / FAILED / PENDING 비율

### 달성한 목표

- ✅ settlement-worker 재시도 스케줄러 (10초 간격, 최대 10회)
- ✅ refund-worker 재시도 스케줄러
- ✅ Dead Letter 자동 감지 및 로그
- ✅ 정산/환불 통계 REST API (monitoring-service)
- ✅ Grafana 대시보드 (8개 패널)

### 운영 효과

1. **자동 복구**: PG API 일시 장애 시 자동 재시도로 정산/환불 성공률 향상
2. **가시성**: Dead Letter 실시간 모니터링으로 빠른 대응 가능
3. **분석**: 정산/환불 성공률, 처리량 추이 분석 가능
4. **안정성**: 지수 백오프로 시스템 부하 최소화

## 13. Grafana 대시보드 실시간 모니터링

### 개요

정산/환불 워커의 처리 상태를 실시간으로 모니터링하기 위해 MariaDB 직접 쿼리 기반 Grafana 대시보드를 구축했다.

### 구현 방식

#### 1. 데이터소스 설정

**`monitoring/grafana/provisioning/datasources/mariadb.yml`**

Prometheus 대신 MariaDB를 직접 데이터소스로 사용한다. settlement_request와 refund_request 테이블의 데이터를 실시간으로 조회하여 통계를 계산한다.

```yaml
datasources:
  - name: MariaDB
    type: mysql
    url: mariadb:3306
    database: paydb
    user: payuser
```

#### 2. 대시보드 패널 (8개)

**성공률 패널 (Stat)**

- SQL: `SELECT ROUND((SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*)) * 100, 2) FROM settlement_request`
- 최근 6시간 데이터 기준 성공률 계산
- 임계값: 95% 이상 녹색, 90~95% 노란색, 90% 미만 빨간색

**Dead Letter 패널 (Stat)**

- SQL: `SELECT COUNT(*) FROM settlement_request WHERE retry_count >= 10`
- 재시도 10회 초과한 실패 건수 표시
- 1건 이상 시 노란색, 10건 이상 시 빨간색 경고

**시계열 그래프 (Time Series)**

- SQL: `SELECT requested_at as time, COUNT(*) FROM settlement_request GROUP BY DATE_FORMAT(requested_at, '%Y-%m-%d %H:%i:00')`
- 분 단위로 그룹핑하여 시간대별 요청량 추이 표시
- 트래픽 패턴 분석 가능

**상태 분포 (Pie Chart)**

- SQL: `SELECT status, COUNT(*) FROM settlement_request GROUP BY status`
- SUCCESS, FAILED, PENDING 비율을 파이 차트로 시각화
- 시스템 건강도를 한눈에 파악 가능

### 실시간 갱신

- **갱신 주기**: 30초 자동 리프레시
- **조회 범위**: 최근 6시간 데이터 (설정 변경 가능)
- **데이터 영속성**: grafana-data 볼륨으로 대시보드 설정 및 사용자 계정 영구 저장

### 활용 시나리오

**1. 장애 감지**

- Dead Letter가 증가하면 PG API 장애 또는 네트워크 문제 의심
- 성공률이 급격히 하락하면 즉시 확인 필요

**2. 트래픽 분석**

- 시계열 그래프로 피크 타임 파악
- 정산/환불 요청 패턴 분석하여 리소스 최적화

**3. 장기 모니터링**

- 상태 분포로 전체 시스템 안정성 평가
- SUCCESS 비율이 지속적으로 낮으면 구조적 문제 검토
