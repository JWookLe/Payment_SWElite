# 7주차: 1000 RPS 달성 및 성능 최적화

## 📋 목차

1. [초기 상황 분석](#초기-상황-분석)
2. [문제 해결 과정](#문제-해결-과정)
3. [최종 성능 결과](#최종-성능-결과)
4. [핵심 기술 적용](#핵심-기술-적용)

---

## 초기 상황 분석

### 문제점

K6 로드 테스트 결과, 심각한 성능 문제 발견:

| 지표                     | 초기 상태       | 목표           |
| ------------------------ | --------------- | -------------- |
| **에러율**         | 99.99%          | < 0.05%        |
| **RPS**            | ~755            | 1000+          |
| **p(95) 레이턴시** | 1,210ms         | < 500ms        |
| **성공 요청**      | 26개 / 280,000+ | 거의 모두 성공 |

### 근본 원인 파악

#### 1차 진단: 동기 블로킹 문제

**파일**: `PaymentEventPublisher.java`

```java
// ❌ 문제 코드
kafkaTemplate.send(message).get(2, TimeUnit.SECONDS);  // 스레드 블로킹!
```

- Kafka 발행을 동기 방식으로 처리
- OutboxPollingScheduler 스레드가 2초간 블로킹
- 처리량 급감 및 타임아웃 발생

#### 2차 진단: 데이터베이스 Deadlock

**에러 로그**:

```
Deadlock found when trying to get lock; try restarting transaction
SQL: SELECT ... FROM outbox_event WHERE published = false FOR UPDATE
```

- 두 VM(172.25.0.37, 172.25.0.79)이 동시에 같은 outbox_event 행에 pessimistic lock 시도
- 경합 발생 → Deadlock → 99.99% 실패율

---

## 문제 해결 과정

### 1단계: 비동기 Kafka 발행으로 전환

#### 변경 내용

**파일**: `PaymentEventPublisher.java`

```java
// ✅ 해결 코드: Non-blocking async pattern
kafkaTemplate.send(message).whenComplete((sendResult, ex) -> {
    if (ex != null) {
        log.error("Kafka publish failed for topic={}, eventId={}", topic, outboxEvent.getId(), ex);
        try {
            circuitBreaker.executeRunnable(() -> {
                throw new KafkaPublishingException("Kafka send failed", ex);
            });
        } catch (Exception ignored) {
            // Event stays in outbox for retry
        }
    } else {
        log.debug("Event published to Kafka topic={}, eventId={}, paymentId={}",
                topic, outboxEvent.getId(), outboxEvent.getAggregateId());
        outboxEvent.markPublished();
        outboxEventRepository.save(outboxEvent);
    }
});
```

**효과**:

- 스레드 블로킹 제거
- 비동기 콜백으로 성공/실패 처리
- Circuit Breaker와 통합

---

### 2단계: Deadlock Retry 로직 추가

#### 변경 내용

**파일**: `OutboxPollingScheduler.java`

```java
private void pollAndPublishWithRetry() {
    int maxAttempts = 3;
    int attempt = 0;

    while (attempt < maxAttempts) {
        try {
            // Fetch unpublished events with pessimistic lock
            List<OutboxEvent> events = transactionTemplate.execute(status -> {
                Pageable pageable = PageRequest.of(0, batchSize);
                return outboxEventRepository.findUnpublishedEventsForUpdate(pageable);
            });

            // Publish events asynchronously
            if (events != null && !events.isEmpty()) {
                for (OutboxEvent event : events) {
                    paymentEventPublisher.publishToKafkaWithCircuitBreaker(event);
                }
            }
            return; // Success

        } catch (org.springframework.dao.CannotAcquireLockException lockEx) {
            attempt++;
            if (attempt >= maxAttempts) {
                log.warn("Deadlock detected {} times, skipping this poll cycle", maxAttempts);
                return;
            }
            // Exponential backoff: 10ms, 20ms, 40ms
            try {
                Thread.sleep(10L * (long) Math.pow(2, attempt - 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            log.debug("Deadlock detected, retrying poll (attempt {}/{})", attempt + 1, maxAttempts);
        }
    }
}
```

**효과**:

- Deadlock 발생 시 exponential backoff로 재시도 (10ms → 20ms → 40ms)
- 일시적 경합 상황 완화
- 에러율 99.99% → 0.0096%로 개선

**1차 테스트 결과**:

```json
{
  "http_reqs": { "rate": 755.8 },
  "http_req_duration": { "p(95)": 1729 },
  "http_req_failed": { "value": 0.000095 }
}
```

---

### 3단계: ShedLock 도입 (분산 락)

#### 문제 인식

Deadlock retry로 안정성은 확보했지만:

- p(95): 1,729ms (목표 500ms 미달)
- interval-ms를 100ms로 줄이면 다시 deadlock 발생 (p(95): 4,974ms)
- **근본 원인**: 두 VM이 동시에 같은 DB 행을 폴링하려는 구조적 문제

#### 해결책: ShedLock (Distributed Locking)

**의존성 추가**: `build.gradle.kts`

```kotlin
// ShedLock for distributed task scheduling
implementation("net.javacrumbs.shedlock:shedlock-spring:5.10.0")
implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.10.0")
```

**설정 파일**: `ShedLockConfiguration.java`

```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}
```

**스케줄러 어노테이션 추가**: `OutboxPollingScheduler.java`

```java
@Scheduled(
    initialDelayString = "${outbox.polling.initial-delay-ms:1000}",
    fixedDelayString = "${outbox.polling.interval-ms:100}"
)
@SchedulerLock(name = "pollAndPublishOutboxEvents",
        lockAtMostFor = "30s",
        lockAtLeastFor = "1s")
public void pollAndPublishOutboxEvents() {
    // ... polling logic
}
```

**DB 테이블 생성**:

```sql
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_at DATETIME(3) NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB;
```

#### ShedLock 동작 원리

```
시간 0ms:   VM1 lock 획득 ✅ → 폴링 실행
           VM2 lock 실패 ❌ → 대기

시간 100ms: VM1 작업 완료, lock 해제
           VM2 lock 획득 ✅ → 폴링 실행
           VM1 lock 실패 ❌ → 대기

시간 200ms: VM2 작업 완료, lock 해제
           VM1 lock 획득 ✅ → 폴링 실행
           ...
```

**효과**:

- **한 번에 하나의 VM만** outbox 폴링 실행
- Deadlock 완전 제거
- 공격적인 폴링 설정 가능 (interval-ms: 100)

**2차 테스트 결과** (ShedLock + interval: 100ms):

```json
{
  "http_reqs": { "rate": 790.3 },
  "http_req_duration": { "p(95)": 297 },
  "http_req_failed": { "value": 0.000098 }
}
```

✅ **p(95): 297ms** - 목표 500ms 달성!

---

### 4단계: 1000 RPS 달성을 위한 최종 튜닝

#### Outbox Polling 공격적 최적화

**파일**: `application.yml`

```yaml
outbox:
  polling:
    enabled: true
    interval-ms: 50              # 500 → 100 → 50 (20회/초 폴링)
    batch-size: 300              # 100 → 200 → 300
    max-retries: 5
    retry-interval-seconds: 1
  dispatcher:
    core-pool-size: 48           # 16 → 32 → 48
    max-pool-size: 96            # 32 → 64 → 96
    queue-capacity: 15000        # 5000 → 10000 → 15000
```

#### Kafka Producer 튜닝

**파일**: `application.yml`

```yaml
spring:
  kafka:
    producer:
      acks: 1                           # Leader만 확인 (빠름 + 안전)
      retries: 3
      request-timeout-ms: 10000
      delivery-timeout-ms: 120000
      batch-size: 32768                 # 16KB → 32KB (배치 크기 2배)
      linger-ms: 50                     # 100ms → 50ms (배치 대기 시간 감소)
      properties:
        max.in.flight.requests.per.connection: 10  # 5 → 10 (병렬 처리)
        buffer.memory: 134217728        # 64MB → 128MB (버퍼 2배)
        compression.type: lz4
        enable.idempotence: false
```

#### K6 시나리오 조정

**파일**: `loadtest/k6/payment-scenario.js`

```javascript
export const options = {
  scenarios: {
    authorize_flow: {
      executor: "constant-arrival-rate",
      rate: 1000,              // 800 → 1000 RPS
      timeUnit: "1s",
      duration: "6m",
      preAllocatedVUs: 800,
      maxVUs: 1500,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1000"],
    payment_errors: ["rate<0.002"],
    payment_authorize_duration: ["p(95)<500"],
  },
};
```

---

## 최종 성능 결과

### 성능 개선 타임라인

| 단계            | RPS                | p(95)              | 에러율  | 주요 변경 사항                |
| --------------- | ------------------ | ------------------ | ------- | ----------------------------- |
| **초기**  | ~0                 | -                  | 99.99%  | 동기 블로킹 + Deadlock        |
| **1단계** | 755.8              | 1,729ms            | 0.0096% | 비동기 Kafka + Deadlock retry |
| **2단계** | 790.3              | **297ms** ✅ | 0.0098% | **ShedLock 도입**       |
| **최종**  | **984.4** ✅ | **392ms** ✅ | 0.012%  | 1000 RPS 튜닝                 |

### 최종 K6 테스트 결과

```json
{
  "metrics": {
    "http_reqs": {
      "count": 354415,
      "rate": 984.4417246901343
    },
    "http_req_duration": {
      "avg": 129.14904373611995,
      "p(90)": 28.200299400000002,
      "p(95)": 391.90387249999657,
      "p(99)": 3199.7551999999987,
      "max": 5883.266497
    },
    "http_req_failed": {
      "value": 0.00012414824428988615
    },
    "payment_errors": {
      "value": 0.00012414824428988615
    }
  }
}
```

### 목표 달성 현황

| 목표 지표                | 목표값       | 실제값 | 달성률   |
| ------------------------ | ------------ | ------ | -------- |
| **RPS**            | 1000         | 984.4  | ✅ 98.4% |
| **p(95) 레이턴시** | < 500ms      | 392ms  | ✅ 달성  |
| **에러율**         | < 0.05%      | 0.012% | ✅ 달성  |
| **안정성**         | Deadlock 0건 | 0건    | ✅ 달성  |

---

## 핵심 기술 적용

### 1. Transactional Outbox Pattern

**구현**:

- HTTP 요청 시 outbox_event 테이블에 이벤트 저장 (빠른 응답)
- 백그라운드 스케줄러가 비동기로 Kafka 발행
- 분리된 처리로 fault isolation 확보

**장점**:

- HTTP 응답 속도 향상
- Kafka 장애 시에도 HTTP 요청 성공
- 이벤트 유실 방지 (DB에 영구 저장)

### 2. Non-blocking Async Kafka Publishing

**Before**:

```java
kafkaTemplate.send(message).get(2, TimeUnit.SECONDS);  // 블로킹!
```

**After**:

```java
kafkaTemplate.send(message).whenComplete((result, ex) -> {
    // 비동기 콜백 처리
});
```

**효과**:

- 스레드 블로킹 제거
- 동시 처리량 극대화
- Circuit Breaker와 자연스러운 통합

### 3. ShedLock (Distributed Locking)

**핵심 개념**:

- 분산 환경에서 스케줄 태스크를 한 인스턴스만 실행
- DB 테이블 기반 락 (MariaDB의 shedlock 테이블)
- 자동 락 해제 (lockAtMostFor: 30초)

**적용 효과**:

```
Without ShedLock:
VM1 폴링 → DB lock 획득 시도
VM2 폴링 → DB lock 획득 시도  ⚠️ Deadlock!

With ShedLock:
VM1 ShedLock 획득 → VM1만 폴링 실행 ✅
VM2 ShedLock 실패 → 대기 ✅
```

### 4. Circuit Breaker Pattern (Resilience4j)

**설정**:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      kafka-publisher:
        failureRateThreshold: 50      # 50% 실패 시 OPEN
        slowCallRateThreshold: 50
        slowCallDurationThreshold: 2000ms
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
```

**동작**:

- CLOSED: 정상 동작, Kafka 발행 시도
- OPEN: Kafka 장애 감지, 즉시 실패 반환 (이벤트는 outbox에 남음)
- HALF_OPEN: 회복 테스트 (3번 시도)

**효과**:

- Kafka 장애 시 cascading failure 방지
- 빠른 실패로 리소스 보호
- 자동 복구 (HALF_OPEN → CLOSED)

### 5. Exponential Backoff Retry

**구현**:

```java
// 10ms → 20ms → 40ms
Thread.sleep(10L * (long) Math.pow(2, attempt - 1));
```

**효과**:

- 일시적 경합 상황 완화
- 시스템 부하 분산
- ShedLock 도입 전 임시 해결책으로 유효

---

## 아키텍처 다이어그램

### 전체 시스템 구조

```
┌─────────────┐
│   K6 Load   │
│   Tester    │  1000 RPS
└──────┬──────┘
       │
       ▼
┌──────────────────────────────────────────┐
│         Nginx Load Balancer              │
└──────┬───────────────────────┬───────────┘
       │                       │
       ▼                       ▼
┌─────────────┐         ┌─────────────┐
│   VM1       │         │   VM2       │
│ 172.25.0.37 │         │ 172.25.0.79 │
│             │         │             │
│ Ingest      │         │ Ingest      │
│ Service     │         │ Service     │
│             │         │             │
│ ┌─────────┐ │         │ ┌─────────┐ │
│ │ Outbox  │ │         │ │ Outbox  │ │
│ │Scheduler│ │         │ │Scheduler│ │
│ │(ShedLock│◄┼─────────┼─┤ShedLock)│ │
│ └────┬────┘ │         │ └────┬────┘ │
└──────┼──────┘         └──────┼──────┘
       │                       │
       │   ┌───────────────────┘
       │   │
       ▼   ▼
┌──────────────┐
│   MariaDB    │
│   (Sharded)  │
│              │
│ - outbox_event
│ - shedlock   │ ◄── Distributed Lock Table
│ - payment    │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│    Kafka     │
│  (Event Bus) │
└──────────────┘
```

### ShedLock 동작 시퀀스

```
Time: 0ms
┌─────┐                  ┌──────────┐                ┌─────┐
│ VM1 │                  │ ShedLock │                │ VM2 │
└──┬──┘                  │  Table   │                └──┬──┘
   │                     └────┬─────┘                   │
   │ Try lock "pollAndPublish"│                         │
   ├─────────────────────────►│                         │
   │         ✅ ACQUIRED      │                         │
   │◄─────────────────────────┤                         │
   │                          │   Try lock              │
   │                          │◄────────────────────────┤
   │                          │      ❌ LOCKED          │
   │                          ├────────────────────────►│
   │                          │                         │
   │ Poll outbox (100ms)      │                         │
   │ Publish to Kafka         │                         │
   │                          │                         │

Time: 150ms (VM1 completes)
   │ Release lock             │                         │
   ├─────────────────────────►│                         │
   │         ✅ RELEASED      │                         │
   │                          │   Try lock              │
   │                          │◄────────────────────────┤
   │                          │      ✅ ACQUIRED        │
   │                          ├────────────────────────►│
   │                          │                         │
   │                          │    Poll outbox (100ms)  │
   │                          │    Publish to Kafka     │
```

---

## 배운 점 및 교훈

### 1. 성능 문제는 계층적으로 나타난다

- 1차 문제: 동기 블로킹 (표면적 증상)
- 2차 문제: Deadlock (근본 원인)
- 최종 해결: ShedLock (구조적 해결책)

### 2. 모니터링과 로그의 중요성

```
ERROR: Deadlock found when trying to get lock
SQL: SELECT ... FOR UPDATE
```

- 로그 없이는 deadlock 진단 불가능
- 구체적인 에러 메시지가 해결의 실마리

### 3. 점진적 최적화의 효과

| 단계 | 변화          | RPS 증가   | p(95) 개선       |
| ---- | ------------- | ---------- | ---------------- |
| 0→1 | 비동기 Kafka  | 0 → 755   | - → 1729ms      |
| 1→2 | ShedLock      | 755 → 790 | 1729 → 297ms ✅ |
| 2→3 | 1000 RPS 튜닝 | 790 → 984 | 297 → 392ms ✅  |

### 4. 분산 시스템의 동시성 제어

- Pessimistic lock만으로는 부족
- 분산 락(ShedLock)이 필수
- DB 기반 락의 장점: 별도 인프라 불필요

---

## 다음 단계 제안

### 1. 추가 최적화 고려사항

- **Connection Pooling**: HikariCP 설정 미세 조정
- **JVM Tuning**: GC 로그 분석 및 최적화 (p99: 3.2초 개선)
- **Kafka Partitioning**: Merchant ID 기반 파티셔닝으로 병렬 처리

### 2. 장애 복구 시나리오 테스트

- VM 하나 다운 시 동작 확인
- MariaDB failover 테스트
- Kafka 장애 시 Circuit Breaker 동작 확인

---

## 결론

### 최종 성과

✅ **1000 RPS 목표 98.4% 달성** (984 RPS)
✅ **p(95) < 500ms 달성** (392ms)
✅ **에러율 99.99% → 0.012%** (8,000배 개선)
✅ **Deadlock 완전 제거** (ShedLock)

### 핵심 기술

1. **Transactional Outbox Pattern** - 안정적 이벤트 발행
2. **Non-blocking Async Kafka** - 스레드 블로킹 제거
3. **ShedLock** - 분산 락으로 deadlock 해결
4. **Circuit Breaker** - 장애 격리
5. **Exponential Backoff** - 경합 완화

### 프로젝트 의의

- 실전 분산 시스템 성능 최적화 경험
- 문제 진단 → 가설 수립 → 검증 → 해결 프로세스 체득
- ShedLock 같은 production-ready 라이브러리 활용 역량
