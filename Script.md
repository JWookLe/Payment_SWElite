# Payment_SWElite 발표 대본

## 발표자 정보
- **발표 대상**: 교수님 및 회사 관계자
- **발표 시간**: 약 40-50분 (Q&A 별도)
- **발표 목표**: 대용량 결제 시스템의 설계, 구현, 최적화 과정을 기술적으로 깊이 있게 설명

---

# 1. 목차 (1분)

안녕하세요. 오늘 발표할 주제는 **"1000 RPS를 처리하는 이벤트 기반 결제 시스템 구축"**입니다.

발표 순서는 다음과 같습니다:

1. 프로젝트 개요 및 소개
2. 시스템 아키텍처 (7개 마이크로서비스)
3. 이벤트 기반 아키텍처 (Kafka Topics & DLQ)
4. 핵심 기술 패턴 (Transactional Outbox, Rate Limiting, 멱등성, 성능 최적화)
5. 병목 현상 분석 및 3단계 솔루션
6. 기술 스택 상세 (Kafka, DB Sharding, 모니터링)
7. 테스트 및 검증 (K6 부하 테스트, Circuit Breaker)
8. AI & MCP 통합
9. 핵심 교훈 및 향후 계획

---

# 2. 프로젝트 개요 (2분)

## 2.1 프로젝트 배경

이 프로젝트는 **대용량 트래픽을 안정적으로 처리할 수 있는 결제 시스템**을 구축하는 것이 목표입니다.

실제 PG사(Payment Gateway)에서 발생하는 문제들을 경험하고 해결하기 위해, 다음과 같은 시나리오를 가정했습니다:

- **초당 1000건 이상의 결제 요청** 처리
- **시스템 일부 장애 시에도 데이터 무손실** 보장
- **중복 결제 방지** (멱등성)
- **실시간 모니터링 및 자동 분석**

## 2.2 핵심 목표

| 목표 | 설명 | 달성 여부 |
|------|------|----------|
| 1000 RPS | 초당 1000건 결제 요청 처리 | ✅ 달성 |
| 99.99% 가용성 | Circuit Breaker로 장애 격리 | ✅ 달성 |
| 데이터 정합성 | Transactional Outbox Pattern | ✅ 달성 |
| 수평 확장성 | DB Sharding + 2 VM 분산 | ✅ 달성 |

---

# 3. 프로젝트 소개 (3분)

## 3.1 전체 결제 흐름

사용자가 결제를 요청하면 다음과 같은 흐름으로 처리됩니다:

```
[사용자] → [Gateway] → [Ingest-Service] → [Kafka] → [Consumer-Worker]
                              ↓                           ↓
                         [MariaDB]                  [Ledger 기록]
                              ↓
                    [Settlement-Worker] → [PG사 API 호출]
```

1. **Authorize (인가)**: 결제 금액을 먼저 확보합니다 (실제 출금 X)
2. **Capture (매입)**: 확보된 금액을 실제로 출금합니다
3. **Refund (환불)**: 출금된 금액을 환불합니다

이 3단계는 실제 카드사/PG사의 결제 흐름과 동일합니다.

## 3.2 왜 이렇게 복잡하게 만들었는가?

단순히 DB에 INSERT하고 끝나는 시스템은 **대용량 트래픽에서 반드시 문제**가 발생합니다:

1. **DB 병목**: 단일 DB는 초당 수백 건 처리가 한계
2. **동시성 이슈**: 같은 결제를 두 번 처리하는 중복 결제
3. **장애 전파**: PG사 API가 느려지면 전체 시스템 마비
4. **데이터 유실**: 카프카 발행 실패 시 이벤트 유실

이 모든 문제를 해결하기 위해 **이벤트 기반 아키텍처**와 **다양한 패턴**을 적용했습니다.

---

# 4. 시스템 아키텍처 - 7개 마이크로서비스 (5분)

## 4.1 마이크로서비스란?

마이크로서비스 아키텍처는 하나의 큰 애플리케이션을 **독립적으로 배포 가능한 작은 서비스들로 분리**하는 설계 방식입니다.

각 서비스는:
- **독립적인 데이터베이스**를 가질 수 있고
- **독립적으로 배포**할 수 있으며
- **독립적으로 확장**할 수 있습니다

## 4.2 7개 마이크로서비스 상세 설명

### 4.2.1 Eureka Server (서비스 디스커버리)
```
포트: 8761
역할: 서비스 등록 및 발견
```

**Eureka란?**
Netflix가 개발한 서비스 디스커버리 서버입니다. 모든 마이크로서비스가 시작될 때 Eureka에 자신을 등록하고, 다른 서비스를 찾을 때 Eureka에 질의합니다.

**왜 필요한가?**
- 서비스가 어느 IP/포트에서 실행 중인지 하드코딩하지 않아도 됨
- 서비스가 죽으면 자동으로 목록에서 제거
- 새 인스턴스가 추가되면 자동으로 로드밸런싱에 포함

```java
// 다른 서비스에서 ingest-service 호출 시
@FeignClient(name = "INGEST-SERVICE")  // Eureka에서 자동으로 IP 찾음
public interface IngestServiceClient {
    @PostMapping("/api/payments/authorize")
    PaymentResponse authorize(PaymentRequest request);
}
```

### 4.2.2 API Gateway (Spring Cloud Gateway)
```
포트: 8080
역할: 외부 요청의 단일 진입점, 라우팅, 로드밸런싱
```

**Gateway란?**
모든 외부 요청이 거치는 **단일 진입점**입니다. 클라이언트는 여러 마이크로서비스의 주소를 알 필요 없이 Gateway 주소만 알면 됩니다.

**주요 기능:**
1. **라우팅**: `/api/payments/*` → INGEST-SERVICE로 전달
2. **로드밸런싱**: 여러 ingest-service 인스턴스에 요청 분산
3. **CORS 처리**: 프론트엔드에서의 Cross-Origin 요청 허용

```yaml
# Gateway 라우팅 설정
spring:
  cloud:
    gateway:
      routes:
        - id: ingest-service
          uri: lb://INGEST-SERVICE  # lb = Load Balanced
          predicates:
            - Path=/api/payments/**, /api/refund/**
```

**성능 설정:**
```yaml
max-connections: 4000      # 최대 동시 연결
connect-timeout: 5000      # 연결 타임아웃 5초
response-timeout: 30s      # 응답 대기 최대 30초
```

### 4.2.3 Ingest-Service (결제 처리 핵심)
```
포트: 8080 (VM1), 8083 (VM2)
역할: 결제 인가/매입/환불 API, 비즈니스 로직 처리
```

**Ingest란?**
"Ingest"는 "섭취하다, 받아들이다"라는 의미입니다. 외부에서 들어오는 결제 요청을 **받아들여서 처리**하는 서비스입니다.

**핵심 기능:**
1. **결제 인가 (Authorize)**: 결제 금액 확보, PG API 호출
2. **결제 매입 (Capture)**: 확보된 금액 실제 출금 요청
3. **결제 환불 (Refund)**: 환불 요청 처리

**적용된 패턴들:**
- Rate Limiting (Token Bucket)
- 멱등성 (2계층 캐시)
- Transactional Outbox Pattern
- Circuit Breaker

```java
// 결제 인가 핵심 로직 (간략화)
@Transactional
public PaymentResponse authorize(PaymentRequest request) {
    // 1. Rate Limit 검사
    rateLimiter.verifyAuthorizeAllowed(request.getMerchantId());

    // 2. 멱등성 체크 (중복 요청인지 확인)
    Optional<PaymentResult> cached = idempotencyCache
        .findAuthorization(request.getMerchantId(), request.getIdempotencyKey());
    if (cached.isPresent()) {
        return cached.get().getResponse();  // 캐시된 응답 반환
    }

    // 3. PG API 호출 (Mock)
    PgAuthResponse pgResponse = pgApiClient.authorize(request);

    // 4. DB 저장 + Outbox 이벤트 저장 (같은 트랜잭션)
    Payment payment = paymentRepository.save(newPayment);
    outboxRepository.save(new OutboxEvent("PAYMENT_AUTHORIZED", payment));

    // 5. 응답 캐싱
    idempotencyCache.store(request, response);

    return response;
}
```

### 4.2.4 Consumer-Worker (원장 기록)
```
포트: 8081
역할: Kafka 이벤트 소비, 복식부기 원장 기록
```

**Consumer-Worker란?**
Kafka에서 결제 완료 이벤트를 받아서 **복식부기 원장(Ledger)**에 기록하는 서비스입니다.

**복식부기(Double-Entry Bookkeeping)란?**
모든 거래를 **차변(Debit)**과 **대변(Credit)**으로 동시에 기록하는 회계 방식입니다.

예시: 10,000원 결제 시
```
| 계정             | 차변(Debit) | 대변(Credit) |
|------------------|-------------|--------------|
| merchant_receivable | 10,000원   |              |
| cash              |             | 10,000원     |
```

**왜 복식부기인가?**
- 항상 차변 합계 = 대변 합계 → **데이터 정합성 검증** 가능
- 회계 감사 시 필수 요구사항
- 잔액 계산이 정확

```java
// 복식부기 원장 기록
@KafkaListener(topics = "payment.captured")
@Transactional
public void handleCaptured(String payload) {
    PaymentCapturedEvent event = parse(payload);

    // 차변: 가맹점 수취 계정 증가
    ledgerRepository.save(LedgerEntry.builder()
        .paymentId(event.getPaymentId())
        .debitAccount("merchant_receivable")
        .amount(event.getAmount())
        .build());

    // 대변: 현금 계정 감소
    ledgerRepository.save(LedgerEntry.builder()
        .paymentId(event.getPaymentId())
        .creditAccount("cash")
        .amount(event.getAmount())
        .build());
}
```

### 4.2.5 Settlement-Worker (정산 처리)
```
포트: 8084
역할: Kafka에서 capture 요청 수신, PG API 호출, 정산 완료 처리
```

**Settlement란?**
"정산"이라는 의미입니다. 인가된 결제를 **실제로 PG사에 요청**하여 돈을 이동시키는 역할입니다.

**처리 흐름:**
```
payment.capture-requested 이벤트 수신
    ↓
PG사 Capture API 호출
    ↓
성공 시: payment.captured 이벤트 발행
실패 시: 재시도 (최대 10회) → 실패 시 settlement.dlq로 이동
```

**재시도 로직:**
```java
@Scheduled(fixedDelay = 10000)  // 10초마다 실행
public void retryPendingSettlements() {
    List<SettlementRequest> pending = repository.findByStatus("PENDING");

    for (SettlementRequest request : pending) {
        if (request.getRetryCount() >= MAX_RETRIES) {
            // DLQ로 이동
            sendToDlq(request);
            continue;
        }

        try {
            pgApiClient.capture(request);
            request.setStatus("SUCCESS");
            kafkaTemplate.send("payment.captured", event);
        } catch (Exception e) {
            request.setRetryCount(request.getRetryCount() + 1);
        }
        repository.save(request);
    }
}
```

### 4.2.6 Refund-Worker (환불 처리)
```
포트: 8085
역할: 환불 요청 처리, PG API 환불 호출
```

Settlement-Worker와 유사한 구조로, **환불 요청을 처리**합니다.

**처리 흐름:**
```
payment.refund-requested 이벤트 수신
    ↓
PG사 Refund API 호출
    ↓
성공 시: payment.refunded 이벤트 발행
실패 시: 재시도 (최대 10회) → 실패 시 refund.dlq로 이동
```

### 4.2.7 Monitoring-Service (모니터링 & 관리)
```
포트: 8082
역할: 관리자 대시보드, K6 테스트 실행, AI 분석
```

**주요 기능:**
1. **Circuit Breaker 상태 조회/테스트**
2. **K6 부하 테스트 실행 및 결과 분석**
3. **결제/정산/환불 통계 조회**
4. **Claude AI를 통한 자동 분석 보고서 생성**

```java
// K6 테스트 실행 API
@PostMapping("/test/k6/{scenario}")
public TestResult runK6Test(@PathVariable String scenario) {
    // K6 프로세스 실행
    ProcessBuilder pb = new ProcessBuilder(
        "k6", "run",
        "--env", "BASE_URL=" + gatewayUrl,
        getScriptPath(scenario)
    );

    Process process = pb.start();
    String output = readOutput(process);

    // AI 분석 요청
    String analysis = claudeClient.analyze(output);

    return new TestResult(output, analysis);
}
```

---

# 5. 마이크로서비스 아키텍처 개요 (2분)

## 5.1 서비스 간 통신 방식

우리 시스템에서는 **두 가지 통신 방식**을 사용합니다:

### 5.1.1 동기 통신 (HTTP/REST)
```
[Client] → HTTP → [Gateway] → HTTP → [Ingest-Service]
```
- 요청-응답 패턴
- 즉시 결과를 받아야 하는 경우 사용
- 예: 결제 인가 API 호출

### 5.1.2 비동기 통신 (Kafka)
```
[Ingest-Service] → Kafka → [Consumer-Worker]
                        → [Settlement-Worker]
                        → [Refund-Worker]
```
- 이벤트 발행-구독 패턴
- 처리 완료를 기다리지 않음
- 예: 결제 완료 후 원장 기록

## 5.2 왜 비동기 통신이 필요한가?

동기 통신만 사용하면:
1. **Cascade Failure**: 하나의 서비스가 느려지면 호출하는 모든 서비스가 느려짐
2. **Blocking**: 응답을 기다리는 동안 스레드가 대기 상태
3. **Tight Coupling**: 서비스 간 강한 결합

비동기 통신의 장점:
1. **Decoupling**: 서비스 간 느슨한 결합
2. **Resilience**: 소비자가 죽어도 메시지는 Kafka에 보관
3. **Scalability**: 소비자를 여러 개 띄워서 병렬 처리 가능

---

# 6. 시스템 아키텍처 개요 - 2 VM 구성 (3분)

## 6.1 VM 분리 전략

우리 시스템은 **2대의 VM**으로 구성됩니다:

### VM1 (172.25.0.37) - State Services
```
역할: 데이터 저장, 메시징, 모니터링
서비스:
├── MariaDB (Shard1) - 포트 13306
├── Redis - 포트 6379
├── Kafka + Zookeeper - 포트 9092, 2181
├── Eureka Server - 포트 8761
├── Ingest-Service-VM1 - 포트 8081
├── Monitoring-Service - 포트 8082
├── Prometheus - 포트 9090
├── Grafana - 포트 3000
└── Jenkins - 포트 8088
```

### VM2 (172.25.0.79) - App Services
```
역할: API 라우팅, 이벤트 처리, 프론트엔드
서비스:
├── MariaDB (Shard2) - 포트 13307
├── Gateway - 포트 8080 (외부 진입점)
├── Ingest-Service-VM2 - 포트 8083
├── Consumer-Worker - 포트 8081
├── Settlement-Worker - 포트 8084
├── Refund-Worker - 포트 8085
└── Frontend (React) - 포트 5173
```

## 6.2 왜 이렇게 분리했는가?

### 이유 1: 장애 격리
- VM1이 죽어도 VM2의 Gateway, Frontend는 동작
- VM2가 죽어도 VM1의 모니터링, DB는 동작

### 이유 2: 리소스 분리
- **State Services (VM1)**: 디스크 I/O, 메모리 집약적 (DB, Kafka)
- **App Services (VM2)**: CPU, 네트워크 집약적 (API 처리)

### 이유 3: 수평 확장 준비
- Ingest-Service가 두 VM에 분산 → 로드밸런싱
- DB Sharding으로 데이터 분산

## 6.3 네트워크 통신 흐름

```
[브라우저]
    │
    ▼ (HTTP :5173)
[Frontend - VM2]
    │
    ▼ (HTTP :8080)
[Gateway - VM2]
    │
    ├──▶ (Eureka 조회) → [Eureka - VM1:8761]
    │
    ▼ (Round Robin)
[Ingest-Service-VM1:8081] 또는 [Ingest-Service-VM2:8083]
    │
    ├──▶ [Redis - VM1:6379] (캐시/Rate Limit)
    ├──▶ [MariaDB Shard1/2] (데이터 저장)
    └──▶ [Kafka - VM1:9092] (이벤트 발행)
            │
            ▼
    [Consumer/Settlement/Refund Workers - VM2]
```

---

# 7. 이벤트 기반 아키텍처 - Kafka Topics (5분)

## 7.1 Kafka란?

**Apache Kafka**는 LinkedIn에서 개발한 **분산 메시징 시스템**입니다.

**핵심 개념:**
- **Producer**: 메시지를 보내는 쪽
- **Consumer**: 메시지를 받는 쪽
- **Topic**: 메시지가 저장되는 카테고리 (메일함)
- **Partition**: Topic을 나눈 단위 (병렬 처리용)
- **Consumer Group**: 같은 메시지를 하나의 Consumer만 처리

**왜 Kafka인가?**
1. **고성능**: 초당 수백만 메시지 처리 가능
2. **내구성**: 디스크에 저장, 서버 재시작해도 메시지 유지
3. **확장성**: Partition 추가로 처리량 증가
4. **재처리**: 과거 메시지 다시 읽기 가능

## 7.2 우리 시스템의 Kafka Topics

| Topic | Partitions | Producer | Consumer | 용도 |
|-------|------------|----------|----------|------|
| payment.authorized | 6 | Ingest | (로깅) | 인가 완료 기록 |
| payment.capture-requested | 6 | Ingest | Settlement | 매입 요청 |
| payment.captured | 6 | Settlement | Consumer | 매입 완료 → 원장 기록 |
| payment.refund-requested | 6 | Ingest | Refund | 환불 요청 |
| payment.refunded | 6 | Refund | Consumer | 환불 완료 → 원장 기록 |
| payment.dlq | 3 | Consumer | (수동) | 처리 실패 메시지 |
| settlement.dlq | 3 | Settlement | (수동) | 정산 실패 메시지 |
| refund.dlq | 3 | Refund | (수동) | 환불 실패 메시지 |

## 7.3 Partition이란? 왜 6개인가?

**Partition**은 Topic을 **물리적으로 나눈 단위**입니다.

```
Topic: payment.captured
├── Partition 0: [msg1, msg4, msg7, ...]
├── Partition 1: [msg2, msg5, msg8, ...]
├── Partition 2: [msg3, msg6, msg9, ...]
├── Partition 3: [...]
├── Partition 4: [...]
└── Partition 5: [...]
```

**Partition의 장점:**
1. **병렬 처리**: 각 Partition을 별도 Consumer가 처리
2. **순서 보장**: 같은 Partition 내에서는 순서 보장
3. **확장성**: Partition 추가 = 처리량 증가

**왜 6개인가?**
- Consumer-Worker의 `concurrency: 6` 설정과 맞춤
- 6개 스레드가 6개 Partition을 각각 처리 → 병렬 처리 극대화

```yaml
# Consumer 설정
spring:
  kafka:
    listener:
      concurrency: 6  # 6개 스레드
    consumer:
      max-poll-records: 500  # 한 번에 500개 메시지
      fetch-min-bytes: 524288  # 512KB 모아서 가져오기
```

## 7.4 Consumer 병렬화란?

**병렬화 전:**
```
[Partition 0] ──┐
[Partition 1] ──┼──▶ [Consumer Thread 1] (순차 처리)
[Partition 2] ──┤
[Partition 3] ──┤
[Partition 4] ──┤
[Partition 5] ──┘
```

**병렬화 후:**
```
[Partition 0] ──▶ [Consumer Thread 1]
[Partition 1] ──▶ [Consumer Thread 2]
[Partition 2] ──▶ [Consumer Thread 3]
[Partition 3] ──▶ [Consumer Thread 4]
[Partition 4] ──▶ [Consumer Thread 5]
[Partition 5] ──▶ [Consumer Thread 6]
```

**효과:**
- 처리량 6배 증가
- 각 스레드가 독립적으로 메시지 처리

## 7.5 전체 이벤트 흐름

```
[결제 요청]
    │
    ▼
[Ingest-Service]
    │ (1) DB 저장: Payment, OutboxEvent
    │
    ▼
[Outbox Polling Scheduler] ─── 50ms마다 실행
    │ (2) 미발행 이벤트 조회
    │ (3) Kafka로 발행
    │
    ▼
[payment.capture-requested] Topic
    │
    ▼
[Settlement-Worker]
    │ (4) PG API 호출
    │ (5) 성공 시 payment.captured 발행
    │
    ▼
[payment.captured] Topic
    │
    ▼
[Consumer-Worker]
    │ (6) 복식부기 원장 기록
    │
    ▼ (환불 요청 시)
[payment.refund-requested] Topic
    │
    ▼
[Refund-Worker]
    │ (7) PG API 환불 호출
    │ (8) 성공 시 payment.refunded 발행
    │
    ▼
[payment.refunded] Topic
    │
    ▼
[Consumer-Worker]
    (9) 환불 원장 기록
```

---

# 8. DLQ 처리 (Dead Letter Queue) (3분)

## 8.1 DLQ란?

**DLQ(Dead Letter Queue)**는 **처리에 실패한 메시지를 보관하는 별도의 큐**입니다.

일반 메시지 큐에서 계속 실패하는 메시지가 있으면:
- 계속 재시도 → 리소스 낭비
- 다른 메시지 처리 지연

**해결책**: 일정 횟수 실패 시 DLQ로 이동 → 나중에 수동 처리

## 8.2 언제 DLQ로 이동하는가?

### 케이스 1: Consumer-Worker 처리 실패
```java
@KafkaListener(topics = "payment.captured")
public void handle(String payload) {
    try {
        // 원장 기록 시도
        ledgerService.record(payload);
    } catch (DataIntegrityViolationException e) {
        // FK 제약 조건 위반 (payment가 없음)
        sendToDlq("payment.dlq", payload, e);
    }
}
```

**발생 상황:**
- Payment가 아직 Shard에 없는데 Captured 이벤트가 먼저 도착
- DB 연결 장애로 저장 실패

### 케이스 2: Settlement-Worker 재시도 초과
```java
@Scheduled(fixedDelay = 10000)
public void retrySettlements() {
    for (SettlementRequest request : pendingRequests) {
        if (request.getRetryCount() >= 10) {
            // 10회 이상 실패 → DLQ로 이동
            sendToDlq("settlement.dlq", request);
            request.setStatus("FAILED");
            continue;
        }

        try {
            pgApi.capture(request);
        } catch (Exception e) {
            request.incrementRetryCount();
        }
    }
}
```

**발생 상황:**
- PG사 API 장기간 장애
- 네트워크 문제로 지속적 타임아웃

## 8.3 DLQ 메시지 형식

```json
{
  "originalTopic": "payment.captured",
  "partition": 2,
  "offset": 45678,
  "payload": {
    "paymentId": 12345,
    "amount": 50000,
    "merchantId": "MERCHANT-123"
  },
  "errorType": "DataIntegrityViolationException",
  "errorMessage": "FK constraint violation: payment_id not found",
  "timestamp": "2025-12-08T15:30:00Z",
  "retryCount": 3
}
```

## 8.4 DLQ 복구 방법

### 방법 1: 자동 재처리 (Scheduled)
```java
@Scheduled(cron = "0 0 * * * *")  // 매시간 실행
public void reprocessDlq() {
    List<DlqMessage> messages = fetchFromDlq("payment.dlq");

    for (DlqMessage msg : messages) {
        try {
            // 원래 로직 재실행
            ledgerService.record(msg.getPayload());
            // 성공 시 DLQ에서 삭제
            deleteDlqMessage(msg);
        } catch (Exception e) {
            // 여전히 실패 → 로그 기록, 알림 발송
            alertService.notify("DLQ 메시지 재처리 실패: " + msg.getId());
        }
    }
}
```

### 방법 2: 수동 처리 (Admin Dashboard)
```
1. Grafana에서 DLQ 메시지 확인
2. 원인 분석 (DB 장애? PG 장애?)
3. 원인 해결 후 재처리 버튼 클릭
4. 또는 수동으로 데이터 보정
```

### 방법 3: Kafka Replay
```bash
# 특정 offset부터 다시 읽기
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payment-consumer \
  --topic payment.captured \
  --reset-offsets --to-offset 45678 \
  --execute
```

## 8.5 DLQ 모니터링 (Grafana)

```sql
-- Settlement DLQ 현황
SELECT
  status,
  COUNT(*) as count,
  AVG(retry_count) as avg_retries
FROM settlement_request
WHERE status = 'FAILED'
GROUP BY status;
```

**알림 설정:**
- DLQ 메시지 > 10건/시간 → Slack 알림
- DLQ 메시지 > 100건/시간 → PagerDuty 호출

---

# 9. Grafana에서 Settlement & Refund 통계 (2분)

## 9.1 Grafana란?

**Grafana**는 **시계열 데이터를 시각화하는 오픈소스 대시보드 도구**입니다.

**특징:**
- 다양한 데이터소스 지원 (Prometheus, MySQL, Elasticsearch 등)
- 실시간 대시보드
- 알림 설정 가능
- 커스텀 쿼리 지원

## 9.2 Settlement & Refund 대시보드

### 패널 1: 정산 성공률
```sql
-- MariaDB 직접 쿼리 (Infinity Plugin)
SELECT
  (COUNT(CASE WHEN status = 'SUCCESS' THEN 1 END) * 100.0 / COUNT(*)) as success_rate
FROM settlement_request
WHERE requested_at > NOW() - INTERVAL 1 HOUR;
```

### 패널 2: 환불 현황
```sql
SELECT
  status,
  COUNT(*) as count
FROM refund_request
WHERE requested_at > NOW() - INTERVAL 24 HOUR
GROUP BY status;
```

### 패널 3: 재시도 분포
```sql
SELECT
  retry_count,
  COUNT(*) as count
FROM settlement_request
GROUP BY retry_count
ORDER BY retry_count;
```

### 패널 4: 시간대별 처리량
```sql
SELECT
  DATE_FORMAT(completed_at, '%Y-%m-%d %H:00') as hour,
  COUNT(*) as completed
FROM settlement_request
WHERE status = 'SUCCESS'
GROUP BY hour
ORDER BY hour;
```

---

# 10. 핵심 기술 패턴 - Transactional Outbox Pattern (5분)

## 10.1 문제 상황: Dual Write Problem

결제 처리 시 두 가지 작업을 해야 합니다:
1. **DB에 결제 정보 저장**
2. **Kafka에 이벤트 발행**

**문제:**
```java
@Transactional
public void processPayment(Payment payment) {
    // 1. DB 저장 (성공)
    paymentRepository.save(payment);

    // 2. Kafka 발행 (실패할 수 있음!)
    kafkaTemplate.send("payment.authorized", event);  // ← 여기서 실패하면?
}
```

**시나리오:**
- DB 저장 성공 → Kafka 발행 실패 → **데이터 불일치!**
- DB에는 결제 있는데, 다른 서비스는 모름
- 원장 기록 안 됨, 정산 안 됨

## 10.2 해결책: Transactional Outbox Pattern

**핵심 아이디어:**
> Kafka에 직접 발행하지 말고, **같은 DB 트랜잭션 안에 Outbox 테이블에 저장**하자.
> 그러면 DB 트랜잭션이 실패하면 둘 다 롤백, 성공하면 둘 다 커밋.

```java
@Transactional
public void processPayment(Payment payment) {
    // 1. DB 저장
    paymentRepository.save(payment);

    // 2. Outbox 테이블에 이벤트 저장 (같은 트랜잭션!)
    outboxRepository.save(new OutboxEvent(
        "PAYMENT_AUTHORIZED",
        payment.getId(),
        toJson(payment)
    ));

    // 여기서 HTTP 응답 반환 (빠름!)
}
```

**Outbox 테이블 구조:**
```sql
CREATE TABLE outbox_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(32),     -- "payment"
    aggregate_id BIGINT,            -- payment_id
    event_type VARCHAR(32),         -- "PAYMENT_AUTHORIZED"
    payload JSON,                   -- 이벤트 데이터
    published TINYINT(1) DEFAULT 0, -- 발행 여부
    published_at TIMESTAMP(3),
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP(3)
);
```

## 10.3 Outbox Polling Scheduler

**별도의 스케줄러가 미발행 이벤트를 Kafka로 발행:**

```java
@Scheduled(fixedDelay = 50)  // 50ms마다 실행
@SchedulerLock(name = "outboxPolling")  // 분산 락
public void pollAndPublish() {
    // 1. 미발행 이벤트 조회
    List<OutboxEvent> events = outboxRepository
        .findByPublishedFalse(PageRequest.of(0, 300));

    for (OutboxEvent event : events) {
        // 2. Circuit Breaker 상태 확인
        if (circuitBreaker.getState() == OPEN) {
            log.warn("CB OPEN - 발행 건너뜀");
            return;
        }

        // 3. Kafka 비동기 발행
        kafkaTemplate.send(topic, event.getPayload())
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    // 4. 발행 성공 → published = true
                    event.markPublished();
                    outboxRepository.save(event);
                } else {
                    // 5. 실패 → 다음 폴링에서 재시도
                    event.incrementRetryCount();
                    outboxRepository.save(event);
                }
            });
    }
}
```

## 10.4 장점

1. **데이터 정합성 보장**: DB와 이벤트가 항상 일치
2. **At-Least-Once Delivery**: 최소 한 번 발행 보장
3. **빠른 응답**: HTTP 응답은 Kafka 발행 기다리지 않음
4. **장애 복구**: Kafka 장애 시 Outbox에 이벤트 보관, 복구 후 재발행

## 10.5 동기 vs 비동기 Kafka 발행

### 동기 발행 (기존 방식)
```java
// 응답까지 평균 50-100ms 추가 소요
kafkaTemplate.send(topic, payload).get();  // 블로킹!
```
- Kafka 응답 기다림 → **느림**
- Kafka 장애 시 → **요청 실패**

### 비동기 발행 (Outbox Pattern)
```java
// 응답은 즉시 반환, 발행은 백그라운드
outboxRepository.save(event);
return response;  // 즉시!
```
- Kafka 응답 안 기다림 → **빠름**
- Kafka 장애 시 → **Outbox에 보관, 나중에 발행**

**성능 비교:**
| 방식 | 평균 응답 시간 | Kafka 장애 시 |
|------|---------------|--------------|
| 동기 발행 | 50-100ms | 요청 실패 |
| Outbox Pattern | 10-20ms | 정상 응답 |

---

# 11. Rate Limiting - Token Bucket (3분)

## 11.1 Rate Limiting이란?

**Rate Limiting**은 **일정 시간 내 요청 수를 제한**하는 기술입니다.

**왜 필요한가?**
1. **DDoS 방어**: 악의적 대량 요청 차단
2. **서버 보호**: 과부하 방지
3. **공정한 리소스 분배**: 특정 사용자가 리소스 독점 방지

## 11.2 Token Bucket 알고리즘

**비유:**
> 양동이(Bucket)에 토큰이 있고, 요청마다 토큰 1개 소모.
> 토큰이 없으면 요청 거부.
> 일정 시간마다 토큰 보충.

**동작 원리:**
```
[양동이] capacity = 100 토큰
    │
    │ 1초마다 10개 토큰 보충 (refill rate)
    │
    ▼
[요청 1] 토큰 1개 소모 → 잔여 99개 → 허용
[요청 2] 토큰 1개 소모 → 잔여 98개 → 허용
   ...
[요청 100] 토큰 1개 소모 → 잔여 0개 → 허용
[요청 101] 토큰 없음 → 거부 (429 Too Many Requests)
```

## 11.3 Redis 기반 구현

```java
public void verifyAuthorizeAllowed(String merchantId) {
    String key = "rate:authorize:" + merchantId;

    // 1. 원자적 증가 (Redis INCR)
    Long count = redisTemplate.opsForValue().increment(key);

    // 2. 첫 요청이면 만료 시간 설정
    if (count == 1L) {
        redisTemplate.expire(key, Duration.ofSeconds(60));
    }

    // 3. 용량 초과 검사
    if (count > 48000) {  // 분당 48000 = 초당 800
        Long ttl = redisTemplate.getExpire(key);
        throw new RateLimitExceededException(
            "Rate limit exceeded. Retry after " + ttl + " seconds"
        );
    }
}
```

## 11.4 우리 시스템의 Rate Limit 설정

```yaml
app:
  rate-limit:
    authorize:
      capacity: 48000      # 분당 48,000건
      window-seconds: 60   # 1분 윈도우
    capture:
      capacity: 48000
      window-seconds: 60
    refund:
      capacity: 30000      # 환불은 더 적게
      window-seconds: 60
```

**계산:**
- 48,000 / 60초 = **초당 800건**
- 목표 1000 RPS에 여유분 확보

## 11.5 Fail-Open 정책

**Redis 장애 시 어떻게 할까?**

```java
try {
    Long count = redisTemplate.opsForValue().increment(key);
    // Rate Limit 검사
} catch (DataAccessException e) {
    log.warn("Redis 연결 실패 - Rate Limit 검사 건너뜀");
    // Fail-Open: 요청 허용
    return;
}
```

**Fail-Open vs Fail-Close:**
- **Fail-Open**: 장애 시 모든 요청 허용 → 가용성 우선
- **Fail-Close**: 장애 시 모든 요청 거부 → 보안 우선

우리는 **가용성을 우선**하여 Fail-Open 정책 채택.

---

# 12. 멱등성 - 2계층 캐시 (4분)

## 12.1 멱등성(Idempotency)이란?

**멱등성**: 같은 요청을 여러 번 보내도 **결과가 동일**한 특성.

**왜 필요한가?**
```
[클라이언트] → 결제 요청 → [서버]
                              │ 처리 완료
                              ▼
[클라이언트] ← 응답 (네트워크 장애로 유실!)
                │
                ▼ 응답 안 왔네? 재전송!
[클라이언트] → 같은 결제 요청 → [서버]
```

**멱등성 없이:**
- 같은 결제가 2번 처리됨 → **중복 결제!**

**멱등성 있으면:**
- 두 번째 요청은 첫 번째 결과 반환 → **안전**

## 12.2 Idempotency Key

클라이언트가 요청 시 **고유한 키**를 함께 전송:

```json
POST /api/payments/authorize
{
  "merchantId": "MERCHANT-123",
  "amount": 50000,
  "currency": "KRW",
  "idempotencyKey": "order-abc-123-payment-1"  // 고유 키
}
```

**서버 로직:**
1. idempotencyKey로 캐시 조회
2. 캐시 있음 → 저장된 응답 반환
3. 캐시 없음 → 결제 처리 후 응답 캐시

## 12.3 2계층 캐시 구조

```
[요청] → [Layer 1: Redis] → Cache Hit? → [응답 반환]
              │                    │
              ▼ Cache Miss         │
         [Layer 2: MariaDB] → Cache Hit? → [Redis에 저장] → [응답 반환]
              │                    │
              ▼ Cache Miss         │
         [결제 처리 실행]              │
              │                    │
              ▼                    │
         [DB 저장] + [Redis 저장] → [응답 반환]
```

## 12.4 왜 2계층인가?

### Layer 1: Redis (빠름, 휘발성)
- **조회 속도**: < 1ms
- **용량**: 제한적 (메모리)
- **내구성**: 서버 재시작 시 데이터 손실 가능
- **TTL**: 10분

### Layer 2: MariaDB (느림, 영구적)
- **조회 속도**: 5-10ms
- **용량**: 무제한 (디스크)
- **내구성**: 영구 저장
- **TTL**: 없음 (영구 보관)

### 조합의 장점
1. **Redis로 빠른 응답**: 대부분의 중복 요청은 Redis에서 처리
2. **DB로 안정성**: Redis 장애 시에도 멱등성 보장
3. **Redis 복구**: DB에서 Redis로 데이터 복원 가능

## 12.5 구현 코드

```java
public Optional<PaymentResult> findAuthorization(
        String merchantId, String idempotencyKey) {

    String cacheKey = "idem:authorize:" + merchantId + ":" + idempotencyKey;

    // Layer 1: Redis 조회
    try {
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Redis cache hit");
            return Optional.of(deserialize(cached));
        }
    } catch (DataAccessException e) {
        log.warn("Redis 조회 실패, DB fallback");
    }

    // Layer 2: MariaDB 조회
    Optional<IdemResponseCache> dbCache = repository.findById(
        new IdemResponseCacheId(merchantId, idempotencyKey)
    );

    if (dbCache.isPresent()) {
        log.debug("DB cache hit, Redis에 복원");
        String body = dbCache.get().getResponseBody();

        // Redis에 복원 (다음 요청은 Redis에서 처리)
        putInRedis(cacheKey, body);

        return Optional.of(deserialize(body));
    }

    return Optional.empty();  // Cache Miss → 결제 처리 필요
}
```

## 12.6 Redis의 역할 정리

| 용도 | Key 패턴 | TTL | 설명 |
|------|----------|-----|------|
| Rate Limiting | `rate:{action}:{merchantId}` | 60초 | 분당 요청 수 카운트 |
| Idempotency | `idem:authorize:{merchantId}:{key}` | 600초 | 중복 요청 캐시 |
| 분산 락 | `lock:{resource}` | 30초 | Outbox 폴링 락 |

---

# 13. 성능 최적화 과정 - 3가지 병목 (5분)

## 13.1 병목 현상 발견

초기 테스트에서 **200 RPS**를 넘지 못했습니다.

**증상:**
- 응답 시간 급증 (P99 > 2초)
- 요청 실패율 증가
- CPU/메모리는 여유 있음 → **코드 문제!**

## 13.2 병목 1: Circuit Breaker 성능 저하

**문제:**
- 초기 Circuit Breaker 구현이 **Count-Based** 방식
- 매 요청마다 카운터 업데이트 → **락 경합**

**Count-Based Circuit Breaker:**
```java
// 문제 있는 코드
synchronized void recordSuccess() {
    successCount++;
    if (successCount + failureCount >= windowSize) {
        evaluateState();
    }
}
```

**증상:**
- 동시 요청 증가 시 대기 시간 증가
- 스레드 블로킹 발생

## 13.3 병목 2: Kafka 동기 발행

**문제:**
- 결제 처리 중 Kafka 발행을 **동기**로 수행
- Kafka 응답 대기 시간이 HTTP 응답에 포함

**동기 발행 코드:**
```java
// 문제 있는 코드
kafkaTemplate.send(topic, payload).get();  // 블로킹 50-100ms
return response;  // Kafka 응답 후에야 반환
```

**영향:**
- 평균 응답 시간 +50-100ms
- Kafka 부하 시 응답 시간 급증

## 13.4 병목 3: 분산 Deadlock 발생

**문제:**
- 두 개의 Ingest-Service가 같은 merchantId 결제 동시 처리
- DB 락 순서가 달라서 **Deadlock**

**Deadlock 시나리오:**
```
[Ingest-VM1]                      [Ingest-VM2]
   │                                  │
   ▼                                  ▼
Lock Payment Row A              Lock Payment Row B
   │                                  │
   ▼                                  ▼
Try Lock Row B (대기)         Try Lock Row A (대기)
   │                                  │
   └───────── DEADLOCK! ──────────────┘
```

**증상:**
- 간헐적 트랜잭션 타임아웃
- 에러율 급증

---

# 14. 솔루션 1단계: 비동기 Kafka 발행 (2분)

## 14.1 해결책: Transactional Outbox Pattern

**핵심 변경:**
> Kafka에 직접 발행하지 않고, **Outbox 테이블에 저장**

**Before (동기):**
```java
@Transactional
public PaymentResponse authorize(PaymentRequest req) {
    Payment payment = save(req);
    kafkaTemplate.send(topic, event).get();  // 블로킹!
    return response;
}
```

**After (비동기):**
```java
@Transactional
public PaymentResponse authorize(PaymentRequest req) {
    Payment payment = save(req);
    outboxRepository.save(new OutboxEvent(...));  // 같은 트랜잭션
    return response;  // 즉시 반환!
}

// 별도 스케줄러가 50ms마다 발행
@Scheduled(fixedDelay = 50)
public void pollAndPublish() {
    List<OutboxEvent> events = findUnpublished();
    events.forEach(this::publishToKafka);
}
```

## 14.2 효과

| 지표 | Before | After | 개선 |
|------|--------|-------|------|
| 평균 응답 시간 | 80ms | 25ms | **69% 감소** |
| P99 응답 시간 | 500ms | 150ms | **70% 감소** |
| 처리량 | 200 RPS | 400 RPS | **2배 증가** |

---

# 15. 솔루션 2단계: VM별 독립적 Shard 분리 (3분)

## 15.1 해결책: Database Sharding

**Sharding이란?**
> 데이터를 **여러 DB에 분산 저장**하여 부하 분산

**분리 기준: merchantId**
- 홀수 merchantId → Shard1 (VM1)
- 짝수 merchantId → Shard2 (VM2)

```java
public static void setShardByMerchantId(String merchantId) {
    int id = extractNumber(merchantId);  // "MERCHANT-123" → 123
    String shard = (id % 2 == 0) ? "shard2" : "shard1";
    ShardContextHolder.setShardKey(shard);
}
```

## 15.2 라우팅 구현

```java
public class ShardRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ShardContextHolder.getShardKey();  // "shard1" or "shard2"
    }
}
```

**DataSource 설정:**
```yaml
# VM1 docker-compose.state.yml
PAYMENT_DB_HOST: mariadb           # Shard1 (로컬)
PAYMENT_DB_HOST_SHARD2: 172.25.0.79  # Shard2 (VM2)

# VM2 docker-compose.app.yml
PAYMENT_DB_HOST: 172.25.0.37       # Shard1 (VM1)
PAYMENT_DB_HOST_SHARD2: mariadb-shard2  # Shard2 (로컬)
```

## 15.3 Deadlock 해결

**샤딩 후:**
- 홀수 merchantId는 항상 Shard1에서만 처리
- 짝수 merchantId는 항상 Shard2에서만 처리
- **같은 Row에 대한 경합 없음** → Deadlock 해결!

## 15.4 효과

| 지표 | Before | After | 개선 |
|------|--------|-------|------|
| Deadlock 발생 | 5-10건/분 | 0건 | **100% 해결** |
| DB 커넥션 풀 사용률 | 95% | 50% | **부하 분산** |
| 처리량 | 400 RPS | 700 RPS | **75% 증가** |

---

# 16. 솔루션 3단계: 상태 기반 Circuit Breaker 최적화 (2분)

## 16.1 해결책: Time-Based Sliding Window

**Count-Based → Time-Based 변경**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      kafka-publisher:
        slidingWindowType: TIME_BASED     # 변경!
        slidingWindowSize: 10             # 10초 윈도우
        failureRateThreshold: 50          # 50% 실패 시 OPEN
        slowCallDurationThreshold: 2000ms # 2초 이상은 Slow
        waitDurationInOpenState: 30s      # 30초 후 HALF_OPEN
```

## 16.2 Time-Based의 장점

- **락 경합 없음**: 시간 기반 집계로 동시성 문제 해결
- **더 정확한 측정**: 최근 N초간의 상태 반영
- **부드러운 전환**: 오래된 데이터 자동 제거

## 16.3 최종 효과

| 지표 | 초기 | 최종 | 개선 |
|------|------|------|------|
| 처리량 | 200 RPS | 1000+ RPS | **5배 증가** |
| 평균 응답 시간 | 80ms | 20ms | **75% 감소** |
| P99 응답 시간 | 500ms | 100ms | **80% 감소** |
| 에러율 | 2% | 0.01% | **99.5% 감소** |

---

# 17. 추가 최적화 사항 (2분)

## 17.1 JVM 튜닝

```bash
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError"
```

- **G1GC**: 대용량 힙에 최적화된 GC
- **MaxGCPauseMillis**: GC 일시 정지 200ms 이하 목표

## 17.2 Tomcat 튜닝

```yaml
server:
  tomcat:
    threads:
      max: 500         # 최대 500 스레드
      min-spare: 100   # 최소 100 스레드 유지
    accept-count: 3000 # 대기열 3000
    max-connections: 5000
```

## 17.3 HikariCP 튜닝

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 600  # 커넥션 풀 크기
      minimum-idle: 100
      connection-timeout: 10000
```

## 17.4 Kafka Producer 튜닝

```yaml
spring:
  kafka:
    producer:
      batch-size: 32768     # 32KB 배치
      linger-ms: 50         # 50ms 대기
      compression-type: lz4 # 압축
      acks: 1               # Leader만 확인
```

---

# 18. 최종 성과: 1000 RPS 달성 (2분)

## 18.1 K6 부하 테스트 결과

```
테스트 시나리오: Full Flow (Authorize → Capture → Refund)
테스트 시간: 6분
목표 RPS: 300 iterations/s (= 900 HTTP requests/s)
최대 VUs: 596

결과:
✓ 총 반복 횟수: 107,904회
✓ 총 HTTP 요청: 323,676건
✓ 성공률: 99.99%
✓ 평균 응답 시간: 18.83ms
✓ P95 응답 시간: 25.53ms
✓ P99 응답 시간: 471ms
```

## 18.2 Authorize Only 테스트

```
테스트 시나리오: Authorize Only
목표 RPS: 1050 requests/s
테스트 시간: 6분

결과:
✓ 처리량: 1,050 RPS 달성
✓ 평균 응답 시간: 23ms
✓ P95 응답 시간: 29ms
✓ 에러율: 0.01%
```

## 18.3 주요 성과 요약

| 지표 | 목표 | 달성 | 상태 |
|------|------|------|------|
| RPS | 1,000 | 1,050 | ✅ 초과 달성 |
| P95 Latency | < 500ms | 25.53ms | ✅ 95% 개선 |
| 에러율 | < 0.1% | 0.01% | ✅ 초과 달성 |
| 가용성 | 99.9% | 99.99% | ✅ 초과 달성 |

---

# 19. 기술 스택 상세 (5분)

## 19.1 전체 기술 스택

| 카테고리 | 기술 | 버전 | 용도 |
|----------|------|------|------|
| Language | Java | 21 | 백엔드 개발 |
| Framework | Spring Boot | 3.3.4 | 마이크로서비스 프레임워크 |
| Gateway | Spring Cloud Gateway | 4.1 | API 라우팅 |
| Discovery | Netflix Eureka | - | 서비스 디스커버리 |
| Database | MariaDB | 11.4 | 결제 데이터 저장 |
| Cache | Redis | 7.4 | Rate Limit, 멱등성 캐시 |
| Messaging | Apache Kafka | 7.6.1 (Confluent) | 이벤트 스트리밍 |
| Resilience | Resilience4j | 2.1.0 | Circuit Breaker |
| Monitoring | Prometheus | - | 메트릭 수집 |
| Visualization | Grafana | - | 대시보드 |
| Load Testing | K6 | - | 성능 테스트 |
| CI/CD | Jenkins | - | 자동 빌드/배포 |
| Container | Docker | - | 컨테이너화 |
| Frontend | React + Vite | - | Admin Dashboard |
| AI | Claude API | 3.5 Sonnet | 자동 분석 |

## 19.2 Kafka 6 Partition & Consumer 병렬화

**Partition 설정:**
```java
@Bean
public NewTopic paymentCapturedTopic() {
    return TopicBuilder.name("payment.captured")
        .partitions(6)      // 6개 파티션
        .replicas(1)
        .build();
}
```

**Consumer 병렬화:**
```yaml
spring:
  kafka:
    listener:
      concurrency: 6  # 6개 스레드 = 6개 파티션
```

**효과:**
- 메시지 처리량 6배 증가
- 각 파티션 독립 처리 → 병렬성 극대화

## 19.3 DB 구조 (2 Shard)

```
[Shard1 - VM1:13306]          [Shard2 - VM2:13307]
├── payment (홀수 merchant)    ├── payment (짝수 merchant)
├── outbox_event              ├── outbox_event
├── ledger_entry              ├── ledger_entry
├── settlement_request        ├── settlement_request
├── refund_request            ├── refund_request
└── idem_response_cache       └── idem_response_cache
```

## 19.4 모니터링 스택

### Prometheus
**역할**: 메트릭 수집 및 저장

**수집 대상:**
- JVM 메트릭 (메모리, GC, 스레드)
- HTTP 요청 메트릭 (RPS, 지연시간, 에러율)
- Kafka 메트릭 (Producer lag, Consumer lag)
- DB 커넥션 풀 메트릭
- Circuit Breaker 상태

**스크래핑 설정:**
```yaml
scrape_configs:
  - job_name: 'ingest-service'
    eureka_sd_configs:
      - server: http://eureka-server:8761/eureka
    metrics_path: /actuator/prometheus
```

### Grafana
**역할**: 시각화 및 알림

**주요 대시보드:**
1. **System Overview**: 전체 서비스 상태
2. **Performance**: RPS, Latency, Error Rate
3. **Settlement & Refund**: 정산/환불 현황
4. **Circuit Breaker**: CB 상태 모니터링

---

# 20. Eureka & Prometheus 화면 (2분)

## 20.1 Eureka Dashboard

```
http://172.25.0.37:8761

등록된 서비스:
┌─────────────────────────────────────────────────────────────┐
│ Application          │ AMIs  │ Availability Zones │ Status │
├─────────────────────────────────────────────────────────────┤
│ INGEST-SERVICE       │ n/a   │ (2)                │ UP     │
│ ├── 172.25.0.37:8081                                       │
│ └── 172.25.0.79:8083                                       │
│ GATEWAY              │ n/a   │ (1)                │ UP     │
│ CONSUMER-WORKER      │ n/a   │ (1)                │ UP     │
│ SETTLEMENT-WORKER    │ n/a   │ (1)                │ UP     │
│ REFUND-WORKER        │ n/a   │ (1)                │ UP     │
│ MONITORING-SERVICE   │ n/a   │ (1)                │ UP     │
└─────────────────────────────────────────────────────────────┘
```

## 20.2 Prometheus Targets

```
http://172.25.0.37:9090/targets

Targets:
┌────────────────────────────────────────────────────────────┐
│ Endpoint                        │ State │ Labels          │
├────────────────────────────────────────────────────────────┤
│ http://eureka-server:8761/...   │ UP    │ job="eureka"   │
│ http://gateway:8080/...         │ UP    │ job="gateway"  │
│ http://172.25.0.37:8081/...     │ UP    │ job="ingest"   │
│ http://172.25.0.79:8083/...     │ UP    │ job="ingest"   │
│ http://consumer-worker:8081/... │ UP    │ job="consumer" │
│ http://settlement:8084/...      │ UP    │ job="settlement"│
│ http://refund:8085/...          │ UP    │ job="refund"   │
│ http://monitoring:8082/...      │ UP    │ job="monitoring"│
└────────────────────────────────────────────────────────────┘
```

---

# 21. Grafana 대시보드 (3분)

## 21.1 메트릭 데이터 흐름

```
[Spring Boot App]
    │ @Timed, @Counted 등 Micrometer 어노테이션
    ▼
[/actuator/prometheus] ← Prometheus가 15초마다 스크래핑
    │
    ▼
[Prometheus TSDB] ← 시계열 데이터 저장
    │
    ▼
[Grafana] ← PromQL로 쿼리
    │
    ▼
[Dashboard] ← 시각화
```

## 21.2 주요 대시보드 패널

### Performance Dashboard
1. **HTTP RPS (Gateway & Ingest)**
   ```promql
   sum(rate(http_server_requests_seconds_count{job="gateway"}[1m]))
   ```

2. **Error Rate**
   ```promql
   sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))
   / sum(rate(http_server_requests_seconds_count[1m])) * 100
   ```

3. **P95 Latency**
   ```promql
   histogram_quantile(0.95,
     sum(rate(http_server_requests_seconds_bucket[1m])) by (le)
   ) * 1000
   ```

4. **HikariCP Connections**
   ```promql
   sum(hikaricp_connections_active{job="ingest-service"}) by (instance)
   ```

5. **Kafka Producer Errors**
   ```promql
   sum(rate(kafka_producer_record_error_total[1m]))
   ```

6. **CPU & Heap Usage**
   ```promql
   process_cpu_usage{job=~"gateway|ingest-service"} * 100
   sum(jvm_memory_used_bytes{area="heap"}) by (application) / 1024 / 1024
   ```

---

# 22. 테스트 및 검증 - Admin Dashboard (2분)

## 22.1 Admin Dashboard 구성

React로 구현된 관리자 대시보드:

```
http://172.25.0.79:5173

┌────────────────────────────────────────────────────────────┐
│                    Payment Admin Dashboard                  │
├────────────────────────────────────────────────────────────┤
│ [Dashboard] [Payments] [Refunds] [System] [Tests]          │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Total        │  │ Success Rate │  │ Avg Latency  │     │
│  │ 1,234,567    │  │ 99.99%       │  │ 23ms         │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│                                                            │
│  ┌────────────────────────────────────────────────────┐   │
│  │ Recent Transactions                                 │   │
│  │ ┌────────┬──────────┬────────┬────────┬────────┐  │   │
│  │ │ ID     │ Merchant │ Amount │ Status │ Time   │  │   │
│  │ ├────────┼──────────┼────────┼────────┼────────┤  │   │
│  │ │ 12345  │ M-001    │ 50,000 │ CAPTURED│ 10:30 │  │   │
│  │ │ 12346  │ M-002    │ 30,000 │ REFUNDED│ 10:31 │  │   │
│  │ └────────┴──────────┴────────┴────────┴────────┘  │   │
│  └────────────────────────────────────────────────────┘   │
│                                                            │
│  [Run K6 Test]  [Test Circuit Breaker]  [Clear Cache]     │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

## 22.2 테스트 버튼들

1. **Run K6 Test**: K6 부하 테스트 실행
2. **Test Circuit Breaker**: 9단계 CB 테스트 시나리오
3. **Clear Cache**: Redis 캐시 초기화
4. **Reset Rate Limit**: Rate Limit 카운터 초기화
5. **Kafka Topic Viewer**: 토픽 메시지 조회
6. **DLQ Viewer**: Dead Letter Queue 조회

---

# 23. K6 부하 테스트 (3분)

## 23.1 K6란?

**K6**는 Grafana Labs에서 개발한 **현대적인 부하 테스트 도구**입니다.

**특징:**
- JavaScript로 테스트 시나리오 작성
- 고성능 (Go 언어로 개발)
- 실시간 메트릭 출력
- Grafana 통합 지원

## 23.2 테스트 시나리오

### 시나리오 1: Authorize Only
```javascript
// payment-scenario.js
export const options = {
  scenarios: {
    authorize_flow: {
      executor: "constant-arrival-rate",
      rate: 1050,           // 초당 1050 요청
      timeUnit: "1s",
      duration: "6m",
      preAllocatedVUs: 800,
      maxVUs: 1500,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],           // 5% 미만 실패
    http_req_duration: ["p(95)<1000"],        // P95 < 1초
    payment_authorize_duration: ["p(95)<500"], // P95 < 500ms
  },
};
```

### 시나리오 2: Full Flow (Authorize → Capture → Refund)
```javascript
// full-flow.js
export default function () {
  group("full-flow", () => {
    // Step 1: Authorize
    const authRes = http.post(`${BASE_URL}/payments/authorize`, payload);
    check(authRes, { "authorize ok": (r) => r.status === 200 });

    sleep(0.5);  // 비동기 처리 대기

    // Step 2: Capture
    const captureRes = http.post(`${BASE_URL}/payments/capture/${paymentId}`);
    check(captureRes, { "capture ok": (r) => r.status === 200 });

    sleep(0.5);

    // Step 3: Refund
    const refundRes = http.post(`${BASE_URL}/payments/refund/${paymentId}`);
    check(refundRes, { "refund ok": (r) => r.status === 200 });
  });
}
```

## 23.3 테스트 결과 분석

```
          /\      Grafana   /‾‾/
     /\  /  \     |\  __   /  /
    /  \/    \    | |/ /  /   ‾‾\
   /          \   |   (  |  (‾)  |
  / __________ \  |_|\\_\  \_____/

     scenarios: (100.00%) 1 scenario, 1000 max VUs

     ✓ authorize status ok
     ✓ capture status ok
     ✓ refund status ok

     checks.........................: 100.00% ✓ 323676      ✗ 0
     data_received..................: 161 MB  447 kB/s
     data_sent......................: 70 MB   194 kB/s
     http_req_duration..............: avg=18.83ms  p(95)=25.53ms
     http_reqs......................: 323676  896/s
     iteration_duration.............: avg=1.06s   p(95)=1.24s
     iterations.....................: 107904  299/s
```

---

# 24. Circuit Breaker 패턴 (4분)

## 24.1 Circuit Breaker란?

**Circuit Breaker**는 전기 회로의 차단기에서 유래한 패턴입니다.

**비유:**
> 집에서 과전류가 흐르면 차단기가 내려가서 화재를 방지합니다.
> 마찬가지로, 외부 서비스가 장애면 Circuit Breaker가 열려서 계속된 호출을 차단합니다.

## 24.2 상태 전이

```
[CLOSED] ─── 정상 상태, 모든 요청 통과
    │
    │ 실패율 > 50% (또는 slow call 비율 > 50%)
    ▼
[OPEN] ─── 차단 상태, 모든 요청 즉시 실패
    │
    │ 30초 대기
    ▼
[HALF_OPEN] ─── 테스트 상태, 제한된 요청 허용
    │
    ├── 테스트 성공 → [CLOSED]
    └── 테스트 실패 → [OPEN]
```

## 24.3 왜 필요한가?

**Circuit Breaker 없이:**
```
[Service A] → [Service B (장애)]
    │              │
    │ 계속 호출     │ 타임아웃 (10초)
    │              │
    ▼              ▼
스레드 고갈      응답 없음
    │
    ▼
[Service A도 장애] ← Cascade Failure!
```

**Circuit Breaker 있으면:**
```
[Service A] → [CB: OPEN] → 즉시 실패 (Fallback 응답)
    │
    │ 스레드 보존
    ▼
[Service A 정상 동작]
```

## 24.4 Kafka Publishing Circuit Breaker

**설정:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      kafka-publisher:
        failureRateThreshold: 50      # 50% 실패 시 OPEN
        slowCallRateThreshold: 50     # 50% slow call 시 OPEN
        slowCallDurationThreshold: 2s # 2초 이상 = slow
        waitDurationInOpenState: 30s  # 30초 후 HALF_OPEN
        permittedNumberOfCallsInHalfOpenState: 3  # 테스트 요청 수
        minimumNumberOfCalls: 5       # 최소 호출 수
```

**적용 코드:**
```java
public void publishToKafka(OutboxEvent event) {
    CircuitBreaker cb = registry.circuitBreaker("kafka-publisher");

    if (cb.getState() == State.OPEN) {
        log.warn("CB OPEN - 발행 건너뜀, Outbox에 보관");
        return;  // 나중에 재시도
    }

    try {
        kafkaTemplate.send(topic, event.getPayload()).get(5, TimeUnit.SECONDS);
        cb.onSuccess(duration, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
        cb.onError(duration, TimeUnit.MILLISECONDS, e);
        throw e;
    }
}
```

## 24.5 9단계 테스트 시나리오

Admin Dashboard에서 실행하는 자동화 테스트:

```
Step 1: 초기 상태 확인 (CLOSED)
Step 2: 정상 요청 5회 (baseline)
Step 3: Kafka 장애 시뮬레이션 시작
Step 4: 실패 요청 5회 → CB OPEN 전환 확인
Step 5: 요청 시도 → 즉시 실패 확인
Step 6: 30초 대기
Step 7: HALF_OPEN 전환 확인
Step 8: 테스트 요청 → 성공 확인
Step 9: CLOSED 복귀 확인
```

---

# 25. Circuit Breaker Grafana 모니터링 (2분)

## 25.1 모니터링 메트릭

```promql
# CB 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state{name="kafka-publisher"}

# 성공/실패 호출 수
resilience4j_circuitbreaker_calls_total{name="kafka-publisher", outcome="success"}
resilience4j_circuitbreaker_calls_total{name="kafka-publisher", outcome="failure"}

# Slow Call 비율
resilience4j_circuitbreaker_slow_call_rate{name="kafka-publisher"}

# 버퍼된 호출 수
resilience4j_circuitbreaker_buffered_calls{name="kafka-publisher"}
```

## 25.2 대시보드 패널

1. **CB State Timeline**: 시간에 따른 상태 변화
2. **Success/Failure Rate**: 성공/실패 비율
3. **Slow Call Rate**: 느린 호출 비율
4. **Call Duration**: 호출 소요 시간 분포

---

# 26. AI & MCP 통합 (5분)

## 26.1 MCP란?

**MCP (Model Context Protocol)**는 Anthropic에서 개발한 **AI 모델과 외부 도구를 연결하는 프로토콜**입니다.

**핵심 개념:**
- **Tool Calling**: AI가 외부 함수/API를 호출
- **Context Injection**: 외부 데이터를 AI 컨텍스트에 주입

```
[사용자] → "K6 테스트 결과 분석해줘"
    │
    ▼
[Claude AI]
    │ (1) K6 데이터 필요 인식
    │ (2) MCP Tool 호출
    ▼
[MCP Server] → analyze_k6_test 함수 실행
    │
    │ (3) 결과 반환
    ▼
[Claude AI]
    │ (4) 분석 보고서 생성
    ▼
[사용자] ← 분석 결과
```

## 26.2 MCP 구조

```
[Claude Desktop / API]
    │
    │ JSON-RPC (stdio)
    ▼
[MCP Server] ─── Node.js 프로세스
    │
    ├── Tool 1: analyze_k6_test
    │   └── K6 결과 파싱 → AI 분석 → 보고서
    │
    ├── Tool 2: analyze_circuit_breaker_test
    │   └── CB 상태 분석 → 권장 사항
    │
    └── Tool 3: analyze_metrics
        └── Prometheus 메트릭 분석
```

## 26.3 MCP Server 구현

```javascript
// mcp-servers/ai-test-analyzer/index.js
const server = new MCPServer({
  name: "ai-test-analyzer",
  version: "1.0.0"
});

server.addTool({
  name: "analyze_k6_test",
  description: "K6 부하 테스트 결과를 분석합니다",
  parameters: {
    testId: { type: "string" },
    scenario: { type: "string" },
    rawData: { type: "string" }
  },
  handler: async ({ testId, scenario, rawData }) => {
    // Claude API 호출
    const analysis = await claude.messages.create({
      model: "claude-3-5-sonnet-20241022",
      messages: [{
        role: "user",
        content: `다음 K6 테스트 결과를 분석해주세요:
          시나리오: ${scenario}
          데이터: ${rawData}

          분석 항목:
          1. 성능 요약
          2. 병목 지점
          3. 개선 권장사항`
      }]
    });

    return analysis.content[0].text;
  }
});
```

## 26.4 Claude Desktop에서 MCP 사용

**설정 (claude_desktop_config.json):**
```json
{
  "mcpServers": {
    "payment-analyzer": {
      "command": "node",
      "args": ["./mcp-servers/ai-test-analyzer/index.js"],
      "env": {
        "ANTHROPIC_API_KEY": "sk-ant-..."
      }
    }
  }
}
```

**사용 예시:**
```
사용자: "방금 K6 테스트 돌렸는데 분석해줘"

Claude: [MCP Tool 호출: analyze_k6_test]
        결과를 분석하고 있습니다...

        ## K6 성능 테스트 분석 보고서

        ### 1. 요약
        - 총 반복 횟수: 107,904회
        - 성공률: 99.99%
        - 평균 응답 시간: 18.83ms

        ### 2. 병목 지점
        - P99 응답 시간 471ms로 상대적으로 높음
        - 간헐적 스파이크 발생

        ### 3. 권장 사항
        - Connection Pool 사이즈 10% 증가 검토
        - GC 로그 분석 필요
```

## 26.5 운영팀에서 MCP 활용 방안

### 활용 1: 자동 장애 분석
```
1. 알림 수신 (Grafana → Slack)
2. Claude Desktop에서 "장애 분석해줘"
3. MCP가 자동으로:
   - Prometheus 메트릭 조회
   - 로그 파일 분석
   - 원인 추정 및 해결책 제시
```

### 활용 2: 정기 리포트 생성
```
1. 매일 아침 "어제 성능 리포트 생성해줘"
2. MCP가 자동으로:
   - 일일 트래픽/에러 통계 수집
   - 이상 징후 분석
   - PDF 리포트 생성
```

### 활용 3: 용량 계획
```
1. "현재 추세로 언제 1000 RPS 한계 도달할까?"
2. MCP가 자동으로:
   - 과거 트래픽 데이터 분석
   - 성장률 계산
   - 스케일업 시점 예측
```

---

# 27. 핵심 교훈 (3분)

## 27.1 기술적 교훈

### 교훈 1: 동기 → 비동기 전환의 위력
> HTTP 응답에서 Kafka 발행을 분리하는 것만으로 **응답 시간 70% 개선**

**적용:**
- Transactional Outbox Pattern
- 비동기 Kafka 발행

### 교훈 2: Sharding은 필수
> 단일 DB의 한계는 명확함. **수평 분할이 유일한 해답**

**적용:**
- merchantId 기반 2-Shard
- 각 VM에 독립적 DB

### 교훈 3: Circuit Breaker로 장애 격리
> 외부 의존성 장애가 전체 시스템 장애로 번지는 것을 방지

**적용:**
- Kafka 발행에 CB 적용
- PG API 호출에 CB 적용

### 교훈 4: 멱등성은 선택이 아닌 필수
> 네트워크는 항상 신뢰할 수 없음. **중복 요청은 반드시 발생**

**적용:**
- 2계층 캐시 (Redis + DB)
- idempotencyKey 기반 중복 검사

### 교훈 5: 모니터링 없이 최적화 없다
> **측정할 수 없으면 개선할 수 없다**

**적용:**
- Prometheus + Grafana
- 실시간 메트릭 수집
- AI 기반 자동 분석

## 27.2 프로젝트 관리 교훈

### 교훈 1: 병목은 예상치 못한 곳에서
- 초기 예상: DB가 병목일 것
- 실제: Circuit Breaker 락 경합, 동기 Kafka 발행

### 교훈 2: 점진적 최적화
- 한 번에 모든 것을 바꾸지 않음
- 각 단계마다 측정하고 검증

### 교훈 3: 실제 부하 테스트 필수
- 단위 테스트만으로는 성능 문제 발견 불가
- K6 같은 도구로 실제 부하 시뮬레이션

---

# 28. 향후 계획 (2분)

## 28.1 단기 계획 (1-2개월)

1. **Kafka 클러스터 확장**
   - 1 broker → 3 broker
   - Partition 6 → 12
   - Replication Factor 3

2. **Redis Sentinel 구성**
   - 고가용성 확보
   - 자동 Failover

3. **DB Read Replica 추가**
   - 읽기 부하 분산
   - 보고서/통계 쿼리 분리

## 28.2 중기 계획 (3-6개월)

1. **Kubernetes 마이그레이션**
   - Docker Compose → K8s
   - HPA (Horizontal Pod Autoscaler)
   - 자동 스케일링

2. **분산 추적 도입**
   - Jaeger 또는 Zipkin
   - 요청 전체 경로 추적

3. **Chaos Engineering**
   - 장애 주입 테스트
   - 복구 시나리오 검증

## 28.3 장기 계획 (6개월+)

1. **Multi-Region 배포**
   - DR (Disaster Recovery) 구성
   - 지역별 지연 시간 최소화

2. **ML 기반 이상 탐지**
   - 자동 알림
   - 예측적 스케일링

---

# 29. 추가 기술 설명 (5분)

## 29.1 Jenkins 자동 빌드

**Jenkins란?**
오픈소스 CI/CD 도구로, 코드 변경 시 자동으로 빌드/테스트/배포를 수행합니다.

**우리 파이프라인:**
```groovy
pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/...'
            }
        }

        stage('Build') {
            steps {
                sh './gradlew build -x test'
            }
        }

        stage('Test') {
            steps {
                sh './gradlew test'
            }
        }

        stage('Docker Build') {
            steps {
                sh 'docker-compose build'
            }
        }

        stage('Deploy') {
            steps {
                sh 'docker-compose up -d'
            }
        }
    }
}
```

**빌드 옵션:**
1. **Local**: 로컬 개발 환경
2. **Single VM**: 단일 VM 배포
3. **Dual VM**: 2 VM 분산 배포

**[skip ci] 기능:**
```bash
git commit -m "docs: update README [skip ci]"
```
- 커밋 메시지에 `[skip ci]` 포함 시 빌드 건너뜀
- 문서만 수정할 때 유용

## 29.2 복식부기 (Double-Entry Bookkeeping)

**복식부기란?**
모든 거래를 **차변(Debit)**과 **대변(Credit)**에 동시에 기록하는 회계 방식입니다.

**기본 원칙:**
> 차변 합계 = 대변 합계 (항상!)

**결제 예시 (50,000원):**

| 단계 | 계정 | 차변 | 대변 | 설명 |
|------|------|------|------|------|
| Capture | merchant_receivable | 50,000 | - | 가맹점 수취액 증가 |
| Capture | customer_funds | - | 50,000 | 고객 자금 감소 |
| Refund | customer_funds | 50,000 | - | 고객 자금 증가 |
| Refund | merchant_receivable | - | 50,000 | 가맹점 수취액 감소 |

**검증 쿼리:**
```sql
-- 차변/대변 균형 검증
SELECT
    SUM(debit_amount) as total_debit,
    SUM(credit_amount) as total_credit,
    SUM(debit_amount) - SUM(credit_amount) as balance
FROM ledger_entry;

-- balance = 0 이면 정상
```

**왜 복식부기인가?**
1. **데이터 무결성 검증**: 항상 균형이 맞아야 함
2. **감사 추적**: 모든 거래 이력 보존
3. **회계 표준 준수**: 금융 서비스 필수 요구사항

## 29.3 시스템 확장성 및 선형 확장 전략

**선형 확장(Linear Scaling)이란?**
> 리소스를 2배로 늘리면 처리량도 2배가 되는 것

**우리 시스템의 확장 전략:**

### 수평 확장 (Scale-Out)
```
현재: 2 VM, 1000 RPS
목표: 4 VM, 2000 RPS (이론상)

[VM1] [VM2] → [VM1] [VM2] [VM3] [VM4]
   │           │
1000 RPS   2000 RPS
```

**확장 시 필요한 변경:**
1. **Shard 추가**: 2 → 4 shard
2. **Kafka Partition 증가**: 6 → 12
3. **Consumer 인스턴스 추가**

### 수직 확장 (Scale-Up)
```
현재: 4 core, 16GB RAM
확장: 8 core, 32GB RAM

- 더 많은 스레드 처리
- 더 큰 커넥션 풀
- 더 많은 메모리 캐시
```

### 병목 없는 설계
```
[Gateway] ──┬──▶ [Ingest-1] ──▶ [Shard-1]
            ├──▶ [Ingest-2] ──▶ [Shard-2]
            ├──▶ [Ingest-3] ──▶ [Shard-3]
            └──▶ [Ingest-4] ──▶ [Shard-4]
                     │
                     ▼
              [Kafka Cluster]
                     │
        ┌────┬────┬────┬────┐
        ▼    ▼    ▼    ▼    ▼
    [Consumer-1] ... [Consumer-N]
```

**핵심 원칙:**
- **Stateless 서비스**: 어느 인스턴스가 처리해도 동일
- **데이터 분산**: Sharding으로 DB 부하 분산
- **메시지 분산**: Partition으로 Kafka 부하 분산
- **캐시 분산**: Redis Cluster로 캐시 부하 분산

---

# 30. 마무리 (1분)

## 30.1 프로젝트 요약

**Payment_SWElite**는 다음을 달성한 **프로덕션 수준의 결제 시스템**입니다:

- ✅ **1000+ RPS** 처리량
- ✅ **99.99%** 가용성
- ✅ **25ms** 평균 응답 시간
- ✅ **데이터 정합성** 100% 보장
- ✅ **장애 자동 복구** (Circuit Breaker)
- ✅ **실시간 모니터링** (Prometheus + Grafana)
- ✅ **AI 기반 분석** (Claude MCP)

## 30.2 핵심 메시지

> "대용량 트래픽 처리는 단순히 서버를 늘리는 것이 아니라,
> **올바른 패턴과 아키텍처**를 적용하는 것입니다."

감사합니다.

---

# 부록: 자주 묻는 질문 (FAQ)

## Q1: 왜 MariaDB를 선택했나요?
A: MySQL 호환성 + 더 나은 성능 + 오픈소스. 대부분의 기업에서 사용 중.

## Q2: Kafka 대신 RabbitMQ는 안 되나요?
A: RabbitMQ도 가능하지만, Kafka는:
- 더 높은 처리량
- 메시지 영구 저장
- 재처리 (replay) 지원

## Q3: Redis가 죽으면 어떻게 되나요?
A:
- Rate Limit: Fail-Open으로 모든 요청 허용
- 멱등성: DB Layer 2에서 처리
- 분산 락: 중복 발행 가능 (멱등성으로 커버)

## Q4: Kafka가 죽으면 어떻게 되나요?
A:
- Circuit Breaker OPEN
- Outbox에 이벤트 보관
- Kafka 복구 후 자동 재발행

## Q5: 실제 PG사와 연동하려면?
A:
- MockPgApiClient → 실제 PG API 클라이언트로 교체
- 인증서, API Key 설정
- 타임아웃, 재시도 로직 조정

---

# 발표 팁

1. **데모 준비**: Grafana 대시보드, Admin Dashboard 미리 열어두기
2. **K6 테스트**: 발표 전 한 번 돌려서 결과 준비
3. **Circuit Breaker 데모**: 9단계 테스트 시연 준비
4. **질문 대비**: FAQ 섹션 숙지

발표 화이팅! 🎯
