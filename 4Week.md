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

## 3. 핵심 패턴

### 비관적 락으로 중복 방지

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

여러 서버가 동시에 실행되어도 같은 이벤트를 중복 처리하지 않음

### Circuit Breaker로 장애 격리

```java
if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
    return; // Kafka 장애 시 폴링 중단
}
```

Kafka 다운 시 불필요한 재시도를 하지 않음

### 지수 백오프로 부하 분산

```java
Instant retryThreshold = Instant.now().minus(Duration.ofSeconds(30));
```

최소 30초 간격으로 재시도해서 시스템 부하 최소화

### 설정 외부화

```yaml
outbox:
  polling:
    interval-ms: 10000     # 폴링 주기
    max-retries: 10        # 최대 재시도
```

코드 수정 없이 운영 중 튜닝 가능

---

## 4. 달성한 목표

- ✅ Outbox 패턴 미구현 문제 진단
- ✅ 2주간 누적된 706개 이벤트 완전 복구
- ✅ 프로덕션 수준 Outbox 폴링 스케줄러 구현
- ✅ Circuit Breaker 통합으로 장애 격리
- ✅ 비관적 락으로 분산 환경 대응
- ✅ Dead Letter 모니터링 추가
- ✅ 운영 환경 설정 외부화

---

## 5. 구현 파일 목록

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

## 6. 승인/정산 분리 아키텍처 (settlement-worker)

### 배경

기존 시스템은 결제 승인과 정산을 하나의 API에서 처리했지만, 실제 PG사에서는 승인과 정산이 분리되어 있음:

- **승인(Authorization)**: 즉시 처리 (카드사 승인)
- **정산(Settlement)**: 비동기 처리 (실제 자금 이동)

### 구현 목표

현업 PG 구조를 따라 승인/정산 분리:

- 결제 상태 모델 확장 (3단계 → 10단계)
- settlement-worker 마이크로서비스 추가
- 이벤트 기반 비동기 처리
- PG API 호출 시뮬레이션

### 구현 내용

#### 1. PaymentStatus 확장

**파일**: `backend/ingest-service/src/main/java/com/example/payment/domain/PaymentStatus.java`

10단계 상태 모델:

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

## 7. 환불 워커 구현 (refund-worker)

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

## 8. 재시도 스케줄러 및 통계 대시보드

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

---

## 9. Kafka DLQ 토픽 및 Grafana 모니터링

### 배경: DB vs Kafka, 어떤 방식이 더 나을까?

재시도 횟수 초과 시 Dead Letter를 모니터링하는 방법을 두 가지 고민했다:

**방법 1: MariaDB에서 조회**

```sql
SELECT COUNT(*) FROM settlement_request WHERE retry_count >= 10;
```

**방법 2: Kafka DLQ 토픽에서 조회**

```
settlement.dlq, refund.dlq 토픽의 메시지 수 조회
```

### 왜 Kafka 토픽 조회로 결정했나?

#### 1. 아키텍처 일관성

**문제점 (DB 조회)**:

- 이벤트는 Kafka로 흐르는데, 모니터링만 DB를 보는 것은 이중 데이터 소스
- 결제 시스템이 이벤트 중심 아키텍처인데, DLQ만 DB에 의존하면 일관성이 깨짐

**해결 (Kafka 조회)**:

- 모든 이벤트 흐름을 Kafka로 통일
- 정상 이벤트도 Kafka, 실패 이벤트(DLQ)도 Kafka에서 조회
- 시스템 전체가 이벤트 중심 아키텍처로 일관성 유지

#### 2. 실시간성

**DB 조회**:

- 재시도 스케줄러가 DB 레코드의 `retry_count`를 업데이트
- 스케줄러 주기(10초)만큼 지연 발생 가능

**Kafka 조회**:

- DLQ 토픽에 메시지가 발행되는 즉시 조회 가능
- 실시간으로 Dead Letter 수 파악

#### 3. 확장성

**DB 조회**:

- settlement_request, refund_request 각각 쿼리 필요
- 테이블이 커지면 COUNT 쿼리 부하 증가

**Kafka 조회**:

- 토픽의 오프셋 차이만 계산 (O(1) 시간 복잡도)
- 메시지가 많아도 조회 속도 일정

#### 4. 이벤트 추적

**DB 조회**:

- 어떤 에러가 발생했는지 보려면 별도 조회 필요
- 원본 이벤트와의 연결 고리 약함

**Kafka 조회**:

- DLQ 메시지 자체에 원본 이벤트, 에러 내용, 재시도 이력 모두 포함
- 한 번에 전체 컨텍스트 확인 가능

### 최종 구조: 하이브리드 접근

Grafana 대시보드는 **두 가지 데이터 소스를 모두 활용**:

1. **MariaDB 데이터소스** (성공률, 요청 추이)

   - 정산/환불 성공률 계산
   - 시간대별 요청량 추이
   - 상태별 분포 (SUCCESS/FAILED/PENDING)
2. **Infinity 플러그인 + Kafka** (Dead Letter)

   - settlement.dlq, refund.dlq 메시지 수 조회
   - monitoring-service API를 통해 Kafka 토픽 직접 접근

이렇게 하면:

- ✅ 정산/환불 처리 통계는 DB에서 효율적으로 조회
- ✅ Dead Letter 모니터링은 Kafka에서 실시간으로 조회
- ✅ 각 데이터 소스의 장점을 최대한 활용

### Kafka Dead Letter Queue 구현

#### DLQ 토픽 생성

```bash
# settlement.dlq 토픽 생성
docker exec pay-kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic settlement.dlq \
  --partitions 3 \
  --replication-factor 1

# refund.dlq 토픽 생성
docker exec pay-kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic refund.dlq \
  --partitions 3 \
  --replication-factor 1
```

#### DLQ 메시지 구조

```json
{
  "settlementRequestId": 123,
  "paymentId": 68435,
  "requestAmount": 250000.00,
  "retryCount": 10,
  "status": "FAILED",
  "pgResponseCode": "TIMEOUT",
  "pgResponseMessage": "PG API 타임아웃",
  "errorMessage": "Max retries exceeded",
  "requestedAt": "2025-11-04T07:00:00Z",
  "lastRetryAt": "2025-11-04T07:05:00Z",
  "timestamp": "2025-11-04T07:05:30Z"
}
```

### Grafana Infinity 플러그인 연동

#### 문제: 아키텍처 일관성

초기에는 MariaDB에서 `retry_count >= 10`인 레코드를 조회했지만, 이벤트 중심 아키텍처와 맞지 않음. Kafka DLQ 토픽을 직접 조회하는 방식으로 변경.

#### 해결: Kafka API + Infinity 플러그인

**1. monitoring-service에 API 추가**

```java
@GetMapping("/settlement-dlq-count")
public Map<String, Object> getSettlementDlqCount() {
    try (KafkaConsumer<String, String> consumer = getConsumer()) {
        String topic = "settlement.dlq";
        List<TopicPartition> partitions = ...;

        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);

        long totalCount = endOffsets.entrySet().stream()
            .mapToLong(e -> e.getValue() - beginningOffsets.get(e.getKey()))
            .sum();

        return Map.of("topic", topic, "count", totalCount);
    }
}
```

**2. Grafana Infinity 데이터소스**

`monitoring/grafana/provisioning/datasources/infinity.yml`:

```yaml
datasources:
  - name: Infinity
    type: yesoreyeram-infinity-datasource
    uid: infinity
    access: proxy
```

**3. Grafana 대시보드 패널**

```json
{
  "type": "stat",
  "title": "Settlement Dead Letters (Kafka)",
  "datasource": {
    "type": "infinity",
    "uid": "infinity"
  },
  "targets": [{
    "type": "json",
    "source": "url",
    "url": "http://pay-monitoring:8082/monitoring/kafka/settlement-dlq-count",
    "url_options": {
      "method": "GET"
    },
    "columns": [{
      "selector": "count",
      "text": "value",
      "type": "number"
    }]
  }]
}
```

### Grafana 대시보드 구성 (8개 패널)

1. **Settlement Success Rate** - MariaDB 쿼리로 성공률 계산
2. **Refund Success Rate** - MariaDB 쿼리로 성공률 계산
3. **Settlement Dead Letters (Kafka)** - Infinity 플러그인으로 Kafka DLQ 조회
4. **Refund Dead Letters (Kafka)** - Infinity 플러그인으로 Kafka DLQ 조회
5. **Settlement Requests Over Time** - MariaDB 시계열 그래프
6. **Refund Requests Over Time** - MariaDB 시계열 그래프
7. **Settlement Status Distribution** - MariaDB 원형 차트
8. **Refund Status Distribution** - MariaDB 원형 차트

### 트러블슈팅

**문제 1: Infinity 플러그인 설치 후 볼륨 마운트로 사라짐**

```dockerfile
# Dockerfile에 설치
RUN grafana-cli plugins install yesoreyeram-infinity-datasource
```

하지만 `grafana-data` 볼륨이 `/var/lib/grafana`를 덮어써서 플러그인 사라짐.

**해결:**

```yaml
# docker-compose.yml
grafana:
  environment:
    GF_INSTALL_PLUGINS: yesoreyeram-infinity-datasource  # 런타임 설치
```

**문제 2: Infinity 플러그인 설정 오류**

`Cannot read properties of undefined (reading 'method')`

플러그인 설정 형식이 잘못됨. `url_options` 형식으로 수정.

**문제 3: Grafana 볼륨 초기화**

기존 볼륨에 낡은 설정이 남아있어 데이터소스 프로비저닝 안됨.

```bash
docker compose down grafana
docker volume rm payment_swelite_grafana-data
docker compose up -d grafana
```

### Kafka Operations MCP 서버

Claude Desktop에서 Kafka DLQ를 자연어로 조회할 수 있도록 MCP 서버 구현:

**기능**:

- Kafka 토픽 목록 조회 (payment.*, settlement.dlq, refund.dlq)
- 토픽별 메시지 수, 파티션 정보, 오프셋 조회
- DLQ 메시지 조회 (최근 N개)

**사용 예시**:

```
사용자: "settlement.dlq에 메시지 있어?"
Claude: 14개 메시지 발견 - 대부분 PG 타임아웃 에러
```

### 달성한 효과

- ✅ 이벤트 중심 아키텍처 일관성 유지 (Kafka 직접 조회)
- ✅ Grafana에서 실시간 DLQ 모니터링
- ✅ Claude Desktop을 통한 자연어 DLQ 조회
- ✅ MariaDB와 Kafka의 하이브리드 데이터소스 활용

---

## 10. Worker 분리 전략 및 회고

### 기존 구조의 문제점

처음엔 consumer-worker 하나로 모든 이벤트를 처리했다. payment.authorized, payment.captured, payment.refunded 토픽을 구독해서 ledger_entry만 생성하는 단순한 구조였다.

근데 실제 PG 연동을 시뮬레이션하려다 보니 문제가 보였다. 정산이나 환불은 외부 PG API를 호출해야 하는데, 이게 실패할 수 있고 재시도도 필요하다. 그런데 회계 장부 기록은 그냥 DB INSERT만 하면 되는 내부 작업이다. 이 둘을 한 worker에서 처리하면 관심사가 섞이고, 장애가 생겼을 때 영향 범위도 커진다.

### 변경: Worker 3개로 분리

결국 역할별로 worker를 분리했다:

**consumer-worker**
- 순수하게 ledger_entry 생성만 담당
- payment.captured, payment.refunded 이벤트를 받으면 복식부기로 기록
- 외부 의존성 없음 (MariaDB만 사용)

**settlement-worker**
- payment.capture-requested 이벤트 받아서 PG 정산 API 호출
- Mock PG API로 1~3초 지연, 5% 실패율 시뮬레이션
- 성공하면 payment.captured 발행 → consumer-worker가 장부 기록
- 실패하면 재시도 스케줄러가 10초마다 재시도 (최대 10회)

**refund-worker**
- payment.refund-requested 받아서 PG 환불 API 호출
- settlement-worker와 같은 패턴 (Mock API, 재시도, DLQ)

분리하고 나니 확실히 깔끔했다. settlement-worker가 PG API 호출 실패로 뻗어도 consumer-worker는 정상 동작하고, 각 worker를 독립적으로 스케일 아웃할 수도 있다.

### 남은 문제: 승인도 분리해야 하나?

그런데 승인(authorize)은 아직도 ingest-service에서 동기로 처리하고 있다. 정산/환불은 -requested 이벤트 발행 → worker가 PG API 호출하는 패턴인데, 승인만 ingest-service에서 직접 DB에 AUTHORIZED 저장하고 끝낸다.

실제로는 승인도 카드사 API를 호출해야 하고, 이게 가장 실패율이 높은 단계다 (한도 초과, 카드 정지 등). 근데 고객은 결제 버튼 누르고 기다리니까 동기 처리는 맞다.

문제는 일관성이다. 정산/환불은 worker가 PG API를 호출하는데, 승인만 ingest-service가 직접 처리하면 패턴이 달라서 헷갈린다. 실제 PG API 연동을 추가하려면 결국 ingest-service 코드를 또 수정해야 한다.

### 향후 계획

**Option 1: 승인도 authorization-worker로 분리**
- payment.authorize-requested 발행 → authorization-worker → PG API 호출
- 동기 응답이 필요하니 Kafka로 하면 복잡함 (request-response 패턴 구현 필요)
- 차라리 ingest-service가 authorization-worker를 동기 호출하는 게 나을 듯

**Option 2: 승인은 그대로 두고 PG API만 추가**
- ingest-service 내에 PG API 클라이언트 추가
- 동기 호출이니까 이게 더 단순할 수도
- 다만 ingest-service가 PG에 직접 의존하게 됨

아직 고민 중이다. 일단 정산/환불 worker 분리로 얻은 게 많으니, 승인은 실제 PG 연동 필요할 때 다시 판단하려고 한다.
