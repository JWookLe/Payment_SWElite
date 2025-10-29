# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Payment_SWElite is an event-driven payment processing system demonstrating production-grade microservices architecture. The system handles payment authorization, capture, and refund flows with strong consistency guarantees, rate limiting, idempotency, and comprehensive observability.

**Key Characteristics:**
- Multi-module Gradle build with Spring Boot 3.3.4 and Java 21
- Event-driven architecture using Kafka for async communication
- Transactional outbox pattern for reliable event publishing
- Redis-backed rate limiting and idempotency caching
- Double-entry bookkeeping via ledger entries
- Prometheus/Grafana monitoring stack
- k6 load testing with Jenkins CI/CD pipeline

## Architecture

### Service Topology

**ingest-service** (port 8080)
- REST API for payment operations (authorize, capture, refund)
- Enforces rate limits and idempotency
- Publishes events to Kafka via transactional outbox pattern
- Dependencies: MariaDB, Redis, Kafka

**consumer-worker** (port 8081)
- Kafka consumer for payment events
- Creates ledger entries for financial audit trail
- Dead Letter Queue (DLQ) handling for failed events
- Dependencies: MariaDB, Kafka

### Data Flow

```
Client → ingest-service → [idempotency check (Redis/DB)]
                       → [rate limit (Redis)]
                       → [Payment + OutboxEvent DB write]
                       → Kafka topics
                       → consumer-worker
                       → [LedgerEntry DB write]
```

### Database Schema

The schema is defined in `backend/ingest-service/src/main/resources/schema.sql`:

- **payment**: Payment records with unique constraint on (merchant_id, idempotency_key)
- **ledger_entry**: Double-entry bookkeeping records (debit_account, credit_account)
- **outbox_event**: Transactional outbox for reliable Kafka publishing
- **idem_response_cache**: Persistent idempotency cache (L2 after Redis)

### Kafka Topics

- `payment.authorized` - Authorization events
- `payment.captured` - Capture/settlement events
- `payment.refunded` - Refund events
- `payment.dlq` - Dead Letter Queue for failed event processing

## Common Development Commands

### Building

**Project uses Gradle Wrapper** - always use `./gradlew` instead of `gradle` for consistency.

```bash
# Build backend services (from repository root)
./gradlew :backend:ingest-service:bootJar :backend:consumer-worker:bootJar

# Build all modules
./gradlew build

# Build individual service
./gradlew :backend:ingest-service:build
./gradlew :backend:consumer-worker:build

# Skip tests
./gradlew build -x test

# Run tests only
./gradlew test

# Clean build
./gradlew clean build
```

**Gradle Structure:**
- Root `settings.gradle.kts` defines multi-module project structure
- Modules: `:backend`, `:backend:ingest-service`, `:backend:consumer-worker`
- Root project name: `payment-platform`

### Frontend

```bash
cd frontend
npm install
npm run dev      # Development server on port 5173
npm run build    # Production build
```

### Docker Compose

```bash
# Start all services
docker compose up --build

# Start specific services
docker compose up -d mariadb redis kafka zookeeper
docker compose up -d ingest-service consumer-worker

# View logs
docker compose logs -f ingest-service
docker compose logs -f consumer-worker

# Stop all services
docker compose down

# Clean up volumes
docker compose down -v
```

### Testing Payment APIs

```bash
# Authorize payment
curl -X POST http://localhost:8080/api/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{
    "merchantId": "TEST_MERCHANT",
    "amount": 50000,
    "currency": "KRW",
    "idempotencyKey": "unique-key-123"
  }'

# Capture payment (replace {paymentId})
curl -X POST http://localhost:8080/api/payments/capture/{paymentId}

# Refund payment (replace {paymentId})
curl -X POST http://localhost:8080/api/payments/refund/{paymentId} \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Customer request"}'
```

### Load Testing (k6)

**k6 부하 테스트는 Jenkins 파이프라인에 포함되지 않습니다.** 필요할 때 로컬에서 직접 실행하세요.

**전제 조건:** 서비스가 실행 중이어야 합니다 (`docker compose up -d` 또는 Jenkins 빌드 완료 후)

```bash
# 기본 실행 (승인 API만 테스트)
docker run --rm --network payment_swelite_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=LOADTEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json

# 정산/환불 단계 포함 (전체 플로우 테스트)
docker run --rm --network payment_swelite_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=LOADTEST \
  -e ENABLE_CAPTURE=true \
  -e ENABLE_REFUND=true \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json

# Windows Git Bash 사용 시 (경로 변환 방지)
MSYS_NO_PATHCONV=1 docker run --rm --network payment_swelite_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=LOADTEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js
```

**k6 스크립트 설정:**
- 시나리오: `loadtest/k6/payment-scenario.js`
- 부하 수준: 초당 최대 200 RPS까지 ramp-up
- 환경 변수로 단계별 토글 가능 (ENABLE_CAPTURE, ENABLE_REFUND)
- 기본값: 승인(Authorize) API만 테스트 (빠른 피드백)

### Monitoring

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
  - Pre-configured dashboard: "Payment Service Overview"
- Application metrics: http://localhost:8080/actuator/prometheus (ingest-service)
- Health check: http://localhost:8080/actuator/health

### Jenkins

- UI: http://localhost:8088
- Pipeline defined in `Jenkinsfile` runs: frontend build → backend build → Docker Compose deployment → health check → smoke test

**Pipeline Details:**
- Uses Jenkins tool: `gradle-8.13`
- Builds with: `./gradlew :backend:ingest-service:bootJar :backend:consumer-worker:bootJar`
- Health check: Retry loop for `/actuator/health` endpoint (최대 60초)
- Smoke test: 실제 결제 승인 요청으로 E2E 검증
- **기본 동작**: 빌드 후 서비스가 계속 실행됨 (개발/검증 가능)

**Pipeline Parameters:**
- `AUTO_CLEANUP` (기본값: false): 체크 시 빌드 완료 후 `docker compose down` 실행
- 체크하지 않으면 서비스가 계속 실행되어 로컬에서 접속/테스트 가능

## Code Architecture Patterns

### Transactional Outbox Pattern

The system uses the outbox pattern to ensure reliable event publishing:

1. Business logic updates domain entities (Payment, LedgerEntry)
2. In the same transaction, an OutboxEvent is persisted
3. After transaction commit, event is published to Kafka
4. On success, OutboxEvent.published flag is set to true

See `PaymentService.publishEvent()` in `backend/ingest-service/src/main/java/com/example/payment/service/PaymentService.java`

### Idempotency Enforcement

Two-layer idempotency:

1. **L1 (Redis)**: Fast cache with TTL (default 600s), key format: `idem:authorize:{merchantId}:{idempotencyKey}`
2. **L2 (Database)**: Persistent storage in `idem_response_cache` table with composite PK
3. **Database constraint**: Unique key on payment(merchant_id, idempotency_key)

See `IdempotencyCacheService` in `backend/ingest-service/src/main/java/com/example/payment/service/`

### Rate Limiting

Redis-backed token bucket per merchant and action:
- authorize: 20/min (1000/min in docker-compose for load testing)
- capture: 40/min (1000/min in docker-compose)
- refund: 20/min (500/min in docker-compose)

Rate limits are configurable via environment variables:
```
APP_RATE_LIMIT_AUTHORIZE_CAPACITY
APP_RATE_LIMIT_CAPTURE_CAPACITY
APP_RATE_LIMIT_REFUND_CAPACITY
```

See `RedisRateLimiter` in `backend/ingest-service/src/main/java/com/example/payment/service/`

### Payment State Machine

```
REQUESTED → COMPLETED → REFUNDED
         ↘ CANCELLED
```

- **authorize()**: Creates payment in REQUESTED status
- **capture()**: Transitions REQUESTED → COMPLETED
- **refund()**: Transitions COMPLETED → REFUNDED

### Ledger Accounting

Double-entry bookkeeping for audit trail:

**Capture**: `merchant_receivable` (debit) ← `cash` (credit)
**Refund**: `cash` (debit) ← `merchant_receivable` (credit)

Consumer-worker creates ledger entries idempotently by checking `existsByPaymentIdAndDebitAccountAndCreditAccount()` before insert.

### Error Handling

**Consumer-Worker** uses `@Transactional` on Kafka listener:
- On exception, transaction rolls back and message is reprocessed
- After max retries (Kafka config), message sent to `payment.dlq` topic
- DLQ messages include: original topic, partition, offset, payload, error details

## Configuration

### Environment Variables

**Database (both services):**
- `PAYMENT_DB_HOST` (default: localhost)
- `PAYMENT_DB_PORT` (default: 3306)
- `PAYMENT_DB_NAME` (default: paydb)
- `PAYMENT_DB_USER` (default: payuser)
- `PAYMENT_DB_PASSWORD` (default: paypass)

**Kafka (both services):**
- `KAFKA_BOOTSTRAP_SERVERS` (default: localhost:9092)

**Redis (ingest-service only):**
- `REDIS_HOST` (default: localhost)
- `REDIS_PORT` (default: 6379)

**Rate Limiting (ingest-service):**
- `APP_RATE_LIMIT_AUTHORIZE_CAPACITY` (default: 20)
- `APP_RATE_LIMIT_AUTHORIZE_WINDOW_SECONDS` (default: 60)
- `APP_RATE_LIMIT_CAPTURE_CAPACITY` (default: 40)
- `APP_RATE_LIMIT_REFUND_CAPACITY` (default: 20)

**Idempotency Cache (ingest-service):**
- `APP_IDEMPOTENCY_CACHE_TTL_SECONDS` (default: 600)

**CORS (ingest-service):**
- `APP_CORS_ALLOWED_ORIGINS` (default: http://localhost:5173)

### Application Properties

Configuration files:
- `backend/ingest-service/src/main/resources/application.yml`
- `backend/consumer-worker/src/main/resources/application.yml`

Key differences:
- ingest-service: port 8080, web-application-type: servlet, JPA ddl-auto: update
- consumer-worker: port 8081, web-application-type: reactive, JPA ddl-auto: none (read-only)

## Testing Strategy

### Unit Tests

```bash
cd backend
gradle test

# Specific module
gradle :ingest-service:test
```

Tests use JUnit 5 (`useJUnitPlatform()` configured in build.gradle.kts).

### Integration Testing

The smoke test in Jenkins pipeline verifies E2E flow:
```bash
curl -X POST http://localhost:8080/api/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"jenkins-1"}'
```

### Load Testing

k6 scenarios in `loadtest/k6/payment-scenario.js`:
- Default: Authorize-only (fast feedback loop)
- Optional: Full flow with capture and refund (enabled via env vars)
- Metrics: request rate, p95 latency, error rate
- Summary exported to JSON for Jenkins archiving

## Key Files Reference

### Project Root Structure

```
payment-platform/                 # Root project
├── settings.gradle.kts           # Multi-module setup (ROOT)
├── gradlew, gradlew.bat          # Gradle wrapper scripts
├── gradle/wrapper/               # Gradle wrapper jar
├── Jenkinsfile                   # CI/CD pipeline
├── docker-compose.yml            # Local orchestration
├── frontend/                     # React + Vite app
├── backend/                      # Backend services
└── loadtest/k6/                  # Load testing scenarios
```

### Backend Structure

```
backend/
├── build.gradle.kts              # Backend module build config
├── ingest-service/
│   ├── build.gradle.kts
│   ├── src/main/java/com/example/payment/
│   │   ├── domain/              # JPA entities (Payment, LedgerEntry, OutboxEvent, IdemResponseCache)
│   │   ├── repository/          # Spring Data JPA repositories
│   │   ├── service/             # Business logic (PaymentService, IdempotencyCacheService, RedisRateLimiter)
│   │   ├── web/                 # Controllers and DTOs
│   │   └── config/              # Kafka, Redis, CORS configs
│   └── src/main/resources/
│       ├── application.yml       # Application config
│       └── schema.sql            # Database DDL
└── consumer-worker/
    ├── build.gradle.kts
    ├── src/main/java/com/example/payment/consumer/
    │   ├── domain/              # JPA entities
    │   ├── repository/          # Spring Data JPA repositories
    │   └── service/
    │       └── PaymentEventListener.java  # @KafkaListener
    └── src/main/resources/
        └── application.yml
```

### Important Classes

- `PaymentService` (ingest-service): Core business logic for authorize/capture/refund
- `PaymentController` (ingest-service): REST endpoints at /payments/*
- `PaymentEventListener` (consumer-worker): Kafka event consumer
- `RedisRateLimiter` (ingest-service): Token bucket rate limiting
- `IdempotencyCacheService` (ingest-service): Two-layer idempotency cache

### Frontend

```
frontend/
├── package.json                 # React + Vite + Axios
├── src/
│   ├── App.jsx                  # Main component with payment UI
│   └── main.jsx                 # Entry point
└── Dockerfile                   # Nginx-based production build
```

## Development Workflow

1. **Local Development**: Use `docker compose up` for full stack or run services individually
2. **Code Changes**:
   - Backend: Modify Java code, rebuild with `gradle build`, restart containers
   - Frontend: `npm run dev` provides hot reload
3. **Testing**: Run unit tests with `gradle test`, smoke test with curl, load test with k6
4. **Observability**: Check Grafana dashboards for metrics, Prometheus for raw data
5. **CI/CD**: Push to main triggers Jenkins pipeline (build → deploy → test)

## Docker Build Context

Both Dockerfiles (`backend/*/Dockerfile`) use multi-stage builds:

1. **Build stage**: Uses `gradle:8.7-jdk21` to compile
   - Copies `settings.gradle.kts` from **repository root**
   - Copies entire `backend/` directory
   - Runs Gradle build with full module path (e.g., `:backend:ingest-service:bootJar`)

2. **Runtime stage**: Uses `eclipse-temurin:21-jre`
   - Copies JAR from build stage
   - Exposes appropriate port (8080 or 8081)

**Important**: Docker context is the repository root, not `backend/` directory.

## Notes

- The system prioritizes financial data integrity with ACID transactions within each service and eventual consistency across services via events
- Payment amounts are stored in smallest currency unit (e.g., Korean won, not decimal)
- Consumer-worker uses WebFlux (reactive) to avoid blocking threads while listening to Kafka
- Rate limits in docker-compose.yml are set high (1000/min) for load testing; production should use lower limits from application.yml defaults
- Idempotency keys should be unique per merchant and should be generated client-side (e.g., UUID)
- The outbox pattern guarantees at-least-once delivery; consumers must be idempotent
- **Always use Gradle Wrapper** (`./gradlew`) for builds to ensure version consistency (Gradle 8.7+ required for Java 21)
