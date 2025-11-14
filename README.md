# Payment_SWElite

## 주차별 목표

### 1주차

- React 목업 스토어와 Spring Boot 기반 결제 API(승인/정산/환불)로 E2E 흐름 구현
- Kafka, Redis, MariaDB, Jenkins가 포함된 Docker Compose 로컬 환경 구축

### 2주차

- [X] Redis 기반 rate limit 및 멱등 캐시 고도화
- [X] Prometheus + Grafana 지표 수집 및 시각화 파이프라인 구성
- [X] k6 부하/스트레스 테스트 시나리오 작성 및 400 RPS 목표 달성
- [X] GitHub Webhook + Jenkins 자동 빌드 파이프라인 구성
- [ ] Settlement/Reconciliation 대비 비동기 처리 보강
- [X] payment.dlq 토픽 재전송 기반 Consumer 예외 처리 보강

### 3주차

- [X] Resilience4j 기반 Circuit Breaker 구현 (Kafka Publisher 보호)
- [X] Circuit Breaker 자동 테스트 및 모니터링 (9단계 시나리오)
- [X] Grafana에 Circuit Breaker 패널 추가 (4개 패널)
- [X] Jenkins 파이프라인에 Circuit Breaker Test 스테이지 추가
- [X] Circuit Breaker 백서 가이드 문서화(한글)
- [X] Spring Cloud Eureka 기반 Service Discovery 구현
- [X] API Gateway 도입 (Spring Cloud Gateway) - Eureka 기반 라우팅
- [X] MCP 서버 3종(서킷 브레이커/Database/Redis) 구축 및 Claude 연동
- [X] Rate Limit 24,000/24,000/24,000 상향 및 k6 400 RPS 시나리오 확정

### 4주차

- [X] Outbox Pattern 장애 진단 (706개 미발행 이벤트 발견)
- [X] 프로덕션 수준 Outbox 폴링 스케줄러 구현
- [X] Circuit Breaker 통합 및 분산 환경 대응 (비관적 락)
- [X] 지수 백오프 재시도 전략 및 Dead Letter 모니터링
- [X] 승인/정산 분리 아키텍처 (settlement-worker 구현)
- [X] 결제 상태 모델 확장 (3단계 → 11단계)
- [X] Mock PG API 시뮬레이션 및 E2E 검증
- [X] refund-worker 마이크로서비스 구현
- [X] Mock PG 환불 API (1~3초 지연, 5% 실패율)
- [X] payment.refund-requested 이벤트 구독 및 환불 처리
- [X] payment.refunded 이벤트 발행 및 Payment 상태 업데이트
- [X] 전체 플로우 E2E 검증 (승인 → 정산 → 환불)
- [X] settlement-worker 재시도 스케줄러 구현 (10초 주기, 최대 10회 재시도)
- [X] refund-worker 재시도 스케줄러 구현 (지수 백오프 적용)
- [X] 정산/환불 통계 REST API 구현 (monitoring-service)
- [X] Grafana Settlement & Refund 대시보드 추가 (8개 패널)
- [X] Dead Letter 자동 감지 및 모니터링 기능
- [X] Kafka DLQ (settlement.dlq, refund.dlq) 토픽 구현
- [X] Grafana Infinity 플러그인 연동으로 Kafka 직접 조회
- [X] Kafka Operations MCP 서버 구현 및 Claude Desktop 연동
- [X] Prometheus 메트릭 수집 대상 확장 (2개 → 7개 전체 마이크로서비스)
- [X] 전체 서비스 통합 모니터링 (Eureka, Gateway, 3개 Worker 포함)
- [X] Grafana 대시보드에서 전체 시스템 가시성 확보

**상세 내역**: [4Week.md](./4Week.md)

### 5주차

- [X] Docker 컨텍스트/이미지 최적화 (`.dockerignore`, 공통 Dockerfile, 비루트 실행)
- [X] Jenkins 파이프라인 재작성 (npm ci, curl-client 기반 헬스/스모크, Auto Cleanup 개선)
- [X] 승인 → 정산 → 환불 이벤트 파이프라인 일관성 (capture-requested 발행, worker 멱등성/신뢰성 강화)
- [X] consumer-worker 경량화 및 DLQ 보강, refund-worker 멱등 처리
- [X] MariaDB 포트 충돌 해소(`13306:3306`) 및 HeidiSQL 접속 가이드 반영
- [X] 관리자 Circuit Breaker 테스트 비동기화 및 자동 복구 스크립트 안정화
- [X] F/E Amend

**상세 내역**: [5Week.md](./5Week.md)

## 서비스 구성 요소

| 구성                         | 설명                                                                                                                               |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **api-gateway**        | Spring Cloud Gateway 기반 API Gateway. Eureka를 통해 ingest-service 동적 라우팅. 모든 클라이언트 요청의 진입점 (포트 8080).        |
| **eureka-server**      | Spring Cloud Eureka 기반 Service Discovery 서버. ingest-service, consumer-worker, api-gateway의 서비스 등록/조회 담당 (포트 8761). |
| **frontend**           | React + Vite로 작성된 목업 스토어 UI. iPhone / Galaxy 등 주요 단말 결제 시나리오 제공. Gateway를 통해 API 호출.                    |
| **ingest-service**     | Spring Boot(Java 21) 기반 결제 API. 승인/정산/환불 처리와 outbox 이벤트 발행 담당. Eureka에 자동 등록. Gateway에 의해 라우팅됨.    |
| **consumer-worker**    | Kafka Consumer. 결제 이벤트를 ledger 엔트리로 반영하고 DLQ 처리 로직 포함. Eureka에 자동 등록.                                     |
| **settlement-worker**  | 정산 전용 마이크로서비스. payment.capture-requested 이벤트 구독, Mock PG API 호출, settlement_request 추적 (포트 8084).            |
| **refund-worker**      | 환불 전용 마이크로서비스. payment.refund-requested 이벤트 구독, Mock PG 환불 API 호출, refund_request 추적 (포트 8085).            |
| **monitoring-service** | Spring Boot 기반 모니터링 REST API. Circuit Breaker 상태, 데이터베이스 쿼리, Redis 캐시 통계, 정산/환불 통계 제공 (포트 8082).     |
| **mariadb**            | paydb 스키마 운영. payment, ledger_entry, outbox_event, idem_response_cache, settlement_request, refund_request 테이블 관리.       |
| **kafka & zookeeper**  | 결제 이벤트 토픽(`payment.authorized`, `payment.captured`, `payment.refunded`)을 호스팅.                                     |
| **redis**              | rate limit 카운터 및 결제 승인 응답 멱등 캐시 저장.                                                                                |
| **jenkins**            | CI 서버. Gradle/NPM 빌드, Docker Compose 배포, k6 부하 테스트 자동화.                                                              |
| **prometheus/grafana** | 애플리케이션 메트릭 수집 및 대시보드 제공. Eureka 서버 및 Gateway 메트릭도 포함.                                                   |

## 주요 데이터베이스 DDL

`backend/ingest-service/src/main/resources/schema.sql` 참고

- `payment`: 결제 상태 및 멱등 키 보관 (11단계 상태 관리)
- `ledger_entry`: 승인/정산/환불 시 생성되는 회계 분개 기록
- `outbox_event`: Kafka 발행 전 이벤트 저장소 (Outbox Pattern)
- `idem_response_cache`: 결제 승인 응답 멱등 캐시
- `settlement_request`: 정산 요청 추적 (상태, 재시도 횟수, PG 거래 ID)
- `refund_request`: 환불 요청 추적 (상태, 재시도 횟수, 부분 환불 금액)

## REST API 요약

| Method   | Path                                  | 설명                                                                                |
| -------- | ------------------------------------- | ----------------------------------------------------------------------------------- |
| `POST` | `/api/payments/authorize`           | 멱등 키 기반 결제 승인 처리 및 outbox 기록 (Gateway를 통해 ingest-service로 라우팅) |
| `POST` | `/api/payments/capture/{paymentId}` | 승인된 결제 정산 처리, ledger 기록, 이벤트 발행 (Gateway를 통해 라우팅)             |
| `POST` | `/api/payments/refund/{paymentId}`  | 정산 완료 결제 환불 처리, ledger 기록, 이벤트 발행 (Gateway를 통해 라우팅)          |

## Kafka 토픽

- `payment.authorized`
- `payment.capture-requested`
- `payment.captured`
- `payment.refund-requested`
- `payment.refunded`
- `payment.dlq`
- `settlement.dlq` (정산 Dead Letter Queue)
- `refund.dlq` (환불 Dead Letter Queue)

## Redis 기반 보호 기능

- 승인 API 응답을 Redis TTL 캐시에 저장해서 멱등성을 보장함. 기본 TTL은 600초 (`APP_IDEMPOTENCY_CACHE_TTL_SECONDS`로 조정 가능).
- 가맹점(`merchantId`)별 승인·정산·환불 API에 Rate Limit이 적용됨. `APP_RATE_LIMIT_*` 환경 변수로 조정 가능하고, Redis 장애 시 fail-open 전략을 사용함.

### 성능 목표별 Rate Limit 설정

| 환경                  | 목표 RPS  | Rate Limit (분)      | 비고        |
| --------------------- | --------- | -------------------- | ----------- |
| **개발**        | ~10 RPS   | 1,000/1,000/500      | 빠른 피드백 |
| **부하 테스트** | 400 RPS   | 30,000/30,000/15,000 | 현재 설정   |
| **운영 (목표)** | 1,000 TPS | 70,000/70,000/35,000 | 최종 목표   |

## Observability (Prometheus & Grafana)

### 설정 및 접속

- `docker compose up -d` 시 Prometheus(9090)와 Grafana(3000)가 함께 기동됨.
- **Prometheus**: http://localhost:9090
  - Status → Targets에서 **전체 7개 마이크로서비스** 메트릭 수집 상태 확인
  - 모니터링 대상: eureka-server, gateway, ingest-service, consumer-worker, monitoring-service, settlement-worker, refund-worker
- **Grafana**: http://localhost:3000
  - 기본 계정: `admin`/`admin`
  - `Payment Service Overview` 대시보드: 요청 속도, p95 지연시간, Kafka 소비량, 에러율
  - `Settlement & Refund Statistics` 대시보드: 정산/환불 성공률, Dead Letter (Kafka 직접 조회), 시계열 추이
    - MariaDB 데이터소스: 성공률, 요청 추이, 상태 분포
    - Infinity 플러그인: Kafka DLQ 메시지 수 (settlement.dlq, refund.dlq)

### Prometheus 메트릭 수집 대상 (전체 7개 서비스)

**파일**: `monitoring/prometheus/prometheus.yml`

| 서비스                       | Port | 메트릭 엔드포인트    | 주요 메트릭                                             |
| ---------------------------- | ---- | -------------------- | ------------------------------------------------------- |
| **eureka-server**      | 8761 | /actuator/prometheus | 서비스 레지스트리 크기, 갱신 임계값, 등록된 인스턴스 수 |
| **gateway**            | 8080 | /actuator/prometheus | Gateway 요청 수, 응답 시간, 라우팅 통계                 |
| **ingest-service**     | 8080 | /actuator/prometheus | HTTP 요청/응답, Circuit Breaker 상태, Outbox 폴링       |
| **consumer-worker**    | 8081 | /actuator/prometheus | Kafka 메시지 소비량, Consumer Lag, Ledger Entry 생성    |
| **monitoring-service** | 8082 | /actuator/prometheus | DB 쿼리 통계, 정산/환불 통계 API 호출                   |
| **settlement-worker**  | 8084 | /actuator/prometheus | 정산 요청 처리, PG API 호출 성공/실패율, 재시도 횟수    |
| **refund-worker**      | 8085 | /actuator/prometheus | 환불 요청 처리, PG API 호출 성공/실패율, 재시도 횟수    |

**모든 서비스 공통 메트릭**:

- `jvm_memory_used_bytes`: JVM 메모리 사용량 (heap, non-heap)
- `http_server_requests_seconds_count`: HTTP 요청 처리 수
- `http_server_requests_seconds_max`: 최대 응답 시간
- `kafka_consumer_fetch_manager_records_consumed_total`: Kafka 메시지 소비 수 (worker 서비스)
- `kafka_consumer_fetch_manager_records_lag_max`: Consumer Lag (worker 서비스)

### 구성 방식

- **Prometheus**: 커스텀 이미지 빌드 (`monitoring/prometheus/Dockerfile`)
  - 설정 파일을 이미지에 포함해서 볼륨 마운트 문제 해결
- **Grafana**: 커스텀 이미지 빌드 (`monitoring/grafana/Dockerfile`)
  - Prometheus, MariaDB, Infinity 데이터소스 자동 프로비저닝
  - Infinity 플러그인 설치 (HTTP JSON API 지원)
  - Payment Service Overview, Settlement & Refund Statistics 대시보드 자동 로드
  - grafana-data 볼륨으로 설정 및 계정 영구 저장

## Load Testing (k6)

### 테스트 시나리오

`loadtest/k6/payment-scenario.js`는 승인 → 정산 → (선택적) 환불 흐름을 검증함. 환경 변수로 각 단계를 토글할 수 있음.

**현재 설정** (400 RPS 목표):

- Warm-up: 50 RPS (30초)
- Ramp-up: 50 → 100 → 200 → 300 → 400 RPS (5분)
- Sustain: 400 RPS (2분)
- Cool-down: 0 RPS (30초)
- **총 테스트 시간**: 8분

### 실행 방법

#### 기본 테스트 (승인만)

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network payment-swelite-pipeline_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
```

#### 전체 플로우 테스트 (승인 → 정산 → 환불)

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network payment-swelite-pipeline_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  -e ENABLE_CAPTURE=true \
  -e ENABLE_REFUND=true \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
```

### 성능 목표

- **에러율**: < 1%
- **p95 응답시간**: < 100ms
- **처리량**: 400 RPS 안정적 처리

## 로컬 실행 방법

1. `docker compose up --build`
   - MariaDB, Redis, Kafka, eureka-server, api-gateway, ingest-service, consumer-worker, frontend, Prometheus, Grafana를 기동함
2. 프런트엔드 접속: http://localhost:5173
3. API 확인 예시 (Gateway를 통한 호출):
   ```bash
   curl -X POST http://localhost:8080/api/payments/authorize \
     -H 'Content-Type: application/json' \
     -d '{
       "merchantId":"M123",
       "amount":10000,
       "currency":"KRW",
       "idempotencyKey":"abc-123"
     }'
   ```
4. 종료: `docker compose down`

## Service Discovery (Spring Cloud Eureka)

Eureka는 마이크로서비스 아키텍처에서 서비스 등록/조회의 중앙 집중식 관리를 제공함.

### 개요

- **서버**: Eureka Server (포트 8761)
  - Self-preservation 비활성화 (개발 환경)
  - 헬스 체크 및 메트릭 노출 (Prometheus 호환)
- **클라이언트**: ingest-service, consumer-worker
  - 자동 서비스 등록 (IP 주소 기반)
  - 레지스트리 주기적 갱신 (30초 기본값)
  - 다운 시 자동 제거

### 설정 및 접속

```bash
# Eureka 대시보드 (실시간 서비스 상태 모니터링)
http://localhost:8761

# 등록된 서비스 확인
curl http://localhost:8761/eureka/apps

# ingest-service 상세 정보
curl http://localhost:8761/eureka/apps/ingest-service

# consumer-worker 상세 정보
curl http://localhost:8761/eureka/apps/consumer-worker
```

### 환경 변수

```yaml
EUREKA_SERVER_URL: http://eureka-server:8761/eureka/  # Eureka 서버 주소
```

### 주요 파일

- `backend/eureka-server/src/main/java/com/example/eureka/EurekaServerApplication.java`: Eureka Server 구현
- `backend/eureka-server/src/main/resources/application.yml`: Eureka 설정
- `backend/ingest-service/src/main/resources/application.yml` (L106-114): Eureka Client 설정
- `backend/consumer-worker/src/main/resources/application.yml` (L48-55): Eureka Client 설정

### Phase 5 스케일링 활용

Eureka는 3개 서버 구조에서 다음과 같이 활용됨:

- Server 1 (API): ingest-service → Eureka에 등록
- Server 2 (Data): consumer-worker → Eureka에 등록
- Server 3 (Infra): eureka-server → 중앙 레지스트리 운영
- API Gateway (추후): Eureka를 통해 서비스 동적 라우팅 가능

---

## Circuit Breaker (Resilience4j)

Kafka 발행 실패로부터 시스템을 보호하는 프로덕션 수준의 Circuit Breaker 구현임. Eureka Service Discovery와 함께 작동하여 서비스 레질리언스 강화.

### 개요

- **프레임워크**: Resilience4j 2.1.0
- **보호 대상**: Kafka Publisher (ingest-service)
- **상태 관리**: CLOSED → OPEN → HALF_OPEN → CLOSED
- **자동 복구**: 의존성 회복 시 자동으로 서비스 복구
- **Eureka 통합**: Circuit Breaker 상태를 Eureka Health Indicator로 노출하여 서비스 상태 모니터링 가능

### 모니터링

```
Circuit Breaker 상태 확인:
curl http://localhost:8080/circuit-breaker/kafka-publisher

응답 예시:
{
  "state": "CLOSED",
  "numberOfSuccessfulCalls": 25,
  "numberOfSlowCalls": 11,
  "slowCallRate": "44.00%",
  "failureRate": "0.00%"
}
```

### 자동 테스트

```bash
# 전체 Circuit Breaker 시나리오 자동 실행 (9단계)
bash scripts/test-circuit-breaker.sh

# Jenkins 파이프라인에서 자동 실행됨
# Smoke Test 다음 "Circuit Breaker Test" 단계 포함
```

### Admin Dashboard 테스트 흐름

- 프론트 관리자 페이지(`/admin`) → **Resilience** 섹션 → `Circuit Breaker` 카드의 “테스트 실행” 버튼을 누르면 Gateway가 `/api/admin/tests/circuit-breaker`로 POST 요청을 보낸다.
- 모니터링 서비스는 즉시 `status=running` 보고서를 반환하고, 백그라운드 스레드에서 `scripts/test-circuit-breaker.sh`를 실행해 Kafka 중단→OPEN→복구→CLOSED까지 9단계를 자동 수행한다.
- 진행 상황은 `GET /api/admin/tests/status/<testId>`(프론트 폴링, 수동 호출 가능)에서 확인할 수 있으며, 약 90초 후 `success`/`failure`와 함께 전체 로그(`rawData.output`)가 저장된다.

```bash
# 수동 실행
curl -X POST http://localhost:8080/api/admin/tests/circuit-breaker \
     -H "Content-Type: application/json" \
     -d '{"testId":"circuit-breaker","generateReport":true}'

# 상태 확인
curl http://localhost:8080/api/admin/tests/status/circuit-breaker
```

- 스크립트는 Kafka 컨테이너를 중지/재기동하면서 `OPEN_STATE_WAIT_SECONDS`, `RECOVERY_READY_CHECKS` 등의 파라미터로 상태를 감시하고, 최종 상태가 `CLOSED`가 되지 않으면 exit code 1과 함께 실패 보고서를 남긴다.

### 실시간 모니터링

- **Prometheus**: http://localhost:9090 → 쿼리 검색 → `resilience4j_circuitbreaker_state`
- **Grafana**: http://localhost:3000 → Dashboards → "Payment Service Overview" → Circuit Breaker 패널

### 상태별 동작

| 상태                           | 의미      | 동작                                  |
| ------------------------------ | --------- | ------------------------------------- |
| **CLOSED (0)**           | 정상      | 모든 요청 통과, 메트릭 기록           |
| **OPEN (1)**             | 장애 감지 | 요청 즉시 차단 (30초 대기)            |
| **HALF_OPEN (2)**        | 복구 시도 | 제한된 요청으로 상태 확인 (최대 3개)  |
| **DISABLED (3)**         | 비활성화  | 항상 요청 통과, 메트릭만 기록         |
| **FORCED_OPEN (4)**      | 강제 차단 | 외부 명령으로 차단 (관리 목적)        |
| **FORCED_HALF_OPEN (5)** | 강제 복구 | 외부 명령으로 복구 시도 (테스트 용도) |

### 관련 문서

- **완전한 가이드**: [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)
  - 구현 상세 설명
  - 수동 테스트 방법
  - 모니터링 설정
  - 트러블슈팅

---

## Service Discovery (Eureka)

마이크로서비스 아키텍처에서 서비스들이 자동으로 서로를 찾을 수 있도록 하는 중앙 레지스트리.

### 개요

- **서버**: Eureka Server (포트 8761)

  - Spring Cloud Netflix Eureka Server 4.1.1
  - 서비스 등록/조회 담당
  - Self-preservation 비활성화 (개발 환경)
- **클라이언트**: ingest-service, consumer-worker

  - 자동 서비스 등록 (IP 기반)
  - 30초 주기 heartbeat
  - 다운 시 자동 제거

### 접속 및 확인

```bash
# Eureka 대시보드 (실시간 서비스 상태)
http://localhost:8761

# 등록된 전체 서비스 조회
curl http://localhost:8761/eureka/apps

# ingest-service 상세 정보
curl http://localhost:8761/eureka/apps/INGEST-SERVICE

# consumer-worker 상세 정보
curl http://localhost:8761/eureka/apps/CONSUMER-WORKER
```

### 설정

```yaml
# docker-compose.yml 환경 변수
EUREKA_SERVER_URL: http://eureka-server:8761/eureka/

# application.yml (클라이언트 설정)
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka/}
    register-with-eureka: true    # 자신을 레지스트리에 등록
    fetch-registry: true           # 레지스트리 주기적 갱신
  instance:
    prefer-ip-address: true        # IP 주소 기반 등록
```

### Phase 5 스케일링 활용

- **Server 1 (API)**: ingest-service 등록 → Eureka 조회로 downstream 발견
- **Server 2 (Data)**: consumer-worker 등록
- **Server 3 (Infra)**: eureka-server 중앙 운영
- **API Gateway**: Eureka 기반 동적 라우팅 가능

---

## API Gateway (Spring Cloud Gateway)

모든 클라이언트 요청을 단일 진입점으로 관리하고, Eureka를 통해 백엔드 서비스로 동적 라우팅함.

### 개요

- **프레임워크**: Spring Cloud Gateway 4.1.1
- **라우팅**: Eureka 기반 동적 라우팅
- **경로 패턴**: `/api/payments/**` → `lb://INGEST-SERVICE`
- **포트**: 8080 (기본값)
- **필터**: StripPrefix=1 (경로에서 `/api` 제거 후 인게스트 서비스로 전달)

### 설정

```yaml
# application.yml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true              # Eureka 기반 자동 라우팅 활성화
          lower-case-service-id: true
      routes:
        - id: ingest-service
          uri: lb://INGEST-SERVICE  # 로드 밸런싱 활성화
          predicates:
            - Path=/api/payments/**
          filters:
            - StripPrefix=1          # /api 경로 제거
```

### 요청 흐름

```
Client 요청: POST /api/payments/authorize
     ↓
API Gateway (포트 8080, 경로 기반 라우팅)
     ↓
StripPrefix 필터 (경로에서 /api 제거)
     ↓
Eureka 조회 (INGEST-SERVICE 발견)
     ↓
ingest-service (포트 8080 내부, /payments/authorize 매핑)
```

### 접속 및 확인

```bash
# Gateway 헬스 체크
curl http://localhost:8080/actuator/health

# Gateway 메트릭 확인
curl http://localhost:8080/actuator/prometheus

# 클라이언트 API 호출 (Gateway를 통함)
curl -X POST http://localhost:8080/api/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"M123","amount":10000,"currency":"KRW","idempotencyKey":"abc-123"}'
```

### 주요 파일

- `backend/gateway/src/main/java/com/example/gateway/GatewayApplication.java`: Gateway 애플리케이션
- `backend/gateway/src/main/resources/application.yml`: 라우팅 및 Eureka 설정
- `backend/gateway/build.gradle.kts`: 의존성 관리

### 모니터링

- **Prometheus**: http://localhost:9090 → `gateway_requests_total` 등 메트릭 수집
- **Grafana**: http://localhost:3000 → "Payment Service Overview" 대시보드에서 Gateway 요청 현황 확인

### Phase 5 확장 시 활용

- **다중 인스턴스**: `lb://INGEST-SERVICE`로 여러 ingest-service 인스턴스에 자동 분산
- **라우트 추가**: 다른 마이크로서비스 추가 시 routes 섹션에 새로운 경로 규칙 추가 가능
- **필터 확장**: rate limiting, 인증, 요청 변환 등 필터 추가 가능

---

## Jenkins 파이프라인 개요

1. 소스 체크아웃
2. 프런트엔드 빌드 (npm install + vite build)
3. 백엔드 빌드 (Gradle build)
4. Docker Compose 기동 (Prometheus/Grafana 포함)
5. 헬스 체크 (최대 60초 재시도)
6. Smoke Test 실행 (실제 결제 승인 요청으로 E2E 검증)
7. 파이프라인 종료 시 docker compose down (AUTO_CLEANUP 파라미터가 true인 경우)

### Jenkins 파라미터

- **AUTO_CLEANUP** (기본값: false): 빌드 완료 후 `docker compose down` 실행 여부
  - 체크하지 않으면 서비스가 계속 실행되어 로컬에서 접속/테스트 가능
  - 체크하면 빌드 완료 후 자동으로 컨테이너 정리

## 성능 최적화 내역

### 데이터베이스 튜닝 (MariaDB)

`docker-compose.yml`에서 고부하 처리를 위한 설정을 적용함:

```yaml
mariadb:
  command:
    - --max-connections=200              # 동시 연결 수 증가
    - --innodb-buffer-pool-size=512M     # 버퍼 풀 확대
    - --innodb-log-file-size=128M        # 로그 파일 크기 증가
    - --innodb-flush-log-at-trx-commit=2 # 성능 향상 (내구성 약간 감소)
```

### 커넥션 풀 최적화 (ingest-service)

HikariCP 설정을 조정해서 400 RPS를 처리함:

```yaml
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 100  # 기본 10 → 100
SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: 20        # 기본 10 → 20
SERVER_TOMCAT_THREADS_MAX: 400                   # 기본 200 → 400
```

### Rate Limit 단계별 설정

부하 테스트를 위해 Rate Limit을 단계적으로 설정함:

- **개발 환경**: 1,000/1,000/500 (분당, authorize/capture/refund)
- **부하 테스트**: 30,000/30,000/15,000 (400 RPS 목표 + 25% 여유분)
- **운영 목표**: 70,000/70,000/35,000 (1,000 TPS 목표)

## 트러블슈팅

### 1. Prometheus 컨테이너 시작 실패

**문제**: Docker 볼륨 마운트 오류 발생

```
error mounting "/var/jenkins_home/.../prometheus.yml" to rootfs:
cannot create subdirectories... not a directory
```

**원인**: Jenkins workspace에서 단일 파일을 컨테이너에 마운트할 때 디렉토리로 인식되는 Docker 버그

**해결**: 커스텀 Dockerfile로 설정 파일을 이미지에 직접 포함함

```dockerfile
FROM prom/prometheus:v2.54.1
COPY prometheus.yml /etc/prometheus/prometheus.yml
EXPOSE 9090
CMD ["--config.file=/etc/prometheus/prometheus.yml"]
```

`docker-compose.yml` 수정:

```yaml
prometheus:
  build:
    context: ./monitoring/prometheus
    dockerfile: Dockerfile
  image: pay-prometheus:local
```

### 2. Grafana 대시보드 미표시

**문제**: Grafana UI에서 "Payment Service Overview" 대시보드가 나타나지 않음

**원인**: 볼륨 마운트로 전달된 provisioning 디렉토리와 대시보드 파일이 컨테이너 내부에서 빈 디렉토리로 생성됨

**해결**: 커스텀 Dockerfile로 모든 설정 파일을 이미지에 포함함

```dockerfile
FROM grafana/grafana:10.4.3
COPY provisioning/datasources /etc/grafana/provisioning/datasources
COPY provisioning/dashboards /etc/grafana/provisioning/dashboards
COPY dashboards /etc/grafana/dashboards
EXPOSE 3000
```

### 3. k6 부하 테스트 85% 실패율

**문제**: 초기 테스트에서 48,599건 요청 중 41,765건 실패 (85.93%)

**원인 분석**:

- Rate Limit: 1,000/min (분당 ~16.6 RPS)
- k6 실제 부하: 7,560/min (126 RPS)
- Rate Limit 초과로 대부분 요청 거부됨

**해결 과정**:

1. **문제 인식**: 무작정 Rate Limit을 높이는 것은 실전과 동떨어짐
2. **목표 설정**: 현재 400 RPS, 최종 1,000 TPS 처리
3. **균형잡힌 접근**:
   - Rate Limit: 15,000/min (250 RPS, 25% 여유분)
   - DB 연결 풀을 증가함
   - MariaDB 성능을 튜닝함
   - k6 시나리오를 점진적 ramp-up으로 수정함

**k6 시나리오 개선**:

```javascript
stages: [
  { duration: "30s", target: 50 },   // Warm-up
  { duration: "1m", target: 100 },   // Ramp-up
  { duration: "2m", target: 150 },   // Increase
  { duration: "2m", target: 200 },   // Target
  { duration: "2m", target: 200 },   // Sustain
  { duration: "30s", target: 0 },    // Cool-down
]
```

## DLQ (Dead Letter Queue) 테스트

### 테스트 목적

Consumer 처리 실패 시 DLQ로 메시지가 전송되는지 검증

### 테스트 방법

#### 방법 1: 존재하지 않는 Payment ID (FK 제약조건 위반)

```bash
# 잘못된 paymentId로 이벤트 전송
echo '{"paymentId":99999,"amount":10000,"occurredAt":"2025-01-01T00:00:00Z"}' | \
docker exec -i pay-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payment.captured

# DLQ 확인
docker exec pay-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment.dlq \
  --from-beginning
```

#### 방법 2: JSON 파싱 실패

```bash
# 잘못된 JSON 전송
echo 'invalid json data' | \
docker exec -i pay-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payment.captured

# DLQ 확인 (위와 동일)
```

#### 방법 3: 프론트엔드 + DB 중지 (실전 시나리오)

```bash
# 1. http://localhost:5173 에서 정상 결제 진행

# 2. MariaDB 중지
docker stop pay-mariadb

# 3. 프론트엔드에서 다시 결제 시도 (승인은 성공, Capture 이벤트 발행)

# 4. Consumer 로그 확인 (DB 연결 실패)
docker logs -f payment_swelite-consumer-worker-1

# 5. MariaDB 재시작
docker start pay-mariadb

# 6. DLQ 확인
docker exec pay-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment.dlq \
  --from-beginning
```

### DLQ 메시지 구조

```json
{
  "originalTopic": "payment.captured",
  "partition": 0,
  "offset": 123,
  "payload": "{원본 이벤트}",
  "errorType": "DataIntegrityViolationException",
  "errorMessage": "상세 에러 메시지",
  "timestamp": "2025-10-21T..."
}
```

## 향후 계획

- Settlement/Reconciliation 대비 비동기 처리 보강
- 추가 대시보드/알람 구성 및 운영 안정화
- 1,000 TPS 목표를 위한 추가 스케일링 및 최적화

## GitHub 웹훅 자동화

### 개요

로컬 Jenkins 인스턴스를 외부에 노출하여 GitHub에서 /github-webhook/ 엔드포인트에 접근할 수 있도록 하고, 모든 푸시에서 파이프라인을 트리거함.

### 단계별 설정

1. **ngrok 토큰 설정**

   - 프로젝트 루트에 `.env` 파일 생성: `NGROK_AUTHTOKEN=<your-token>`
   - 저장소의 `.gitignore`에 이미 `.env`가 제외되어 있으므로 안전함
2. **ngrok 프로필로 Jenkins 시작**

   ```bash
   docker compose --profile ngrok up -d jenkins ngrok
   ```

   ngrok 컨테이너는 GitHub의 트래픽을 `pay-jenkins:8080`으로 전달하고 로컬 검사기 UI를 `http://localhost:4040`에 노출함.
3. **GitHub 웹훅 설정**

   - `http://localhost:4040` 열고 HTTPS 포워딩 URL 복사 (예: `https://abcd1234.ngrok.io`)
   - GitHub 저장소 웹훅 URL을 `https://abcd1234.ngrok.io/github-webhook/`로 설정하고 "Push 이벤트만" 옵션 선택
4. **Jenkins 파이프라인 트리거**

   - `Jenkinsfile`에 이미 `githubPush()` 포함되어 있으므로 ngrok을 통해 들어오는 모든 푸시가 자동으로 파이프라인 시작함.

> **보안 주의**: `.env` 파일은 `.gitignore`에 이미 제외되어 있으므로 안전함. 작업 완료 후 `docker compose down ngrok`으로 ngrok 컨테이너 종료 또는 전체 스택 중단.

---

## MCP 서버 (Model Context Protocol)

AI 기반 시스템 모니터링 및 디버깅을 위한 Claude Desktop 통합 MCP 서버를 제공합니다.

### 개요

MCP(Model Context Protocol)는 AI 모델이 외부 시스템과 상호작용할 수 있게 하는 표준 프로토콜입니다. 이 프로젝트는 6개의 MCP 서버를 포함하여 Claude가 자연어로 결제 시스템을 모니터링하고 디버깅할 수 있습니다.

### MCP 서버 목록

#### 1. Circuit Breaker MCP

**위치**: `mcp-servers/circuit-breaker-mcp`

**기능**:

- Circuit Breaker 상태 조회 (CLOSED/OPEN/HALF_OPEN)
- Kafka 헬스 체크
- 실패 패턴 분석
- 장애 진단 및 권장사항 제공

**사용 예시**:

```
사용자: "서킷 브레이커 상태 확인해줘"
Claude: ✅ CLOSED - 정상 작동 중, 실패율 0.5%
```

#### 2. Database Query MCP

**위치**: `mcp-servers/database-query-mcp`

**기능**:

- 결제 내역 자연어 쿼리
- 원장 엔트리 조회
- 미발행 이벤트 탐지
- 복식부기 검증
- 결제 통계 생성

**사용 예시**:

```
사용자: "지난 1시간 실패한 결제 보여줘"
Claude: 📊 3개 발견: #123 (10,000원), #456 (25,000원), #789 (50,000원)
```

#### 3. Redis Cache MCP

**위치**: `mcp-servers/redis-cache-mcp`

**기능**:

- Rate Limit 상태 확인
- 멱등성 키 조회
- Redis 통계 (메모리, Hit Rate)
- Rate Limit 초기화
- TTL 분석

**사용 예시**:

```
사용자: "MERCHANT_X의 Rate Limit 확인"
Claude: ✅ OK - 250/1000 사용 (25%), 리셋까지 45초
```

#### 4. Kafka Operations MCP

**위치**: `mcp-servers/kafka-operations-mcp`

**기능**:

- Kafka 토픽 목록 조회 (payment.*, settlement.dlq, refund.dlq)
- 토픽별 메시지 수, 파티션 정보, 오프셋 조회
- DLQ 메시지 조회 (최근 N개)
- 토픽 생성/삭제
- Kafka 클러스터 상태 확인

**사용 예시**:

```
사용자: "settlement.dlq에 메시지 있어?"
Claude: 📊 14개 메시지 발견 - 대부분 PG 타임아웃 에러
```

#### 5. Loadtest MCP

**위치**: `mcp-servers/loadtest-mcp`

**기능**:

- k6 부하 테스트 실행 및 모니터링
- 테스트 시나리오 관리
- 실시간 성능 메트릭 조회
- 테스트 결과 분석

**사용 예시**:

```
사용자: "400 RPS 부하 테스트 실행해줘"
Claude: ✅ 테스트 시작 - 평균 응답시간 45ms, 에러율 0.2%
```

#### 6. System Health MCP

**위치**: `mcp-servers/system-health-mcp`

**기능**:

- 전체 시스템 헬스 체크
- 마이크로서비스 상태 모니터링
- 리소스 사용량 확인 (CPU, 메모리, 디스크)
- 알림 및 장애 감지

**사용 예시**:

```
사용자: "시스템 전체 상태 확인"
Claude: ✅ 모든 서비스 정상 - CPU 45%, 메모리 62%, ingest-service 응답시간 23ms
```

### 설치 및 설정

#### 1. MCP 서버 빌드

```bash
# 각 MCP 서버 디렉토리에서
cd mcp-servers/circuit-breaker-mcp
npm install && npm run build

cd ../database-query-mcp
npm install && npm run build

cd ../redis-cache-mcp
npm install && npm run build

cd ../kafka-operations-mcp
npm install && npm run build

cd ../loadtest-mcp
npm install && npm run build

cd ../system-health-mcp
npm install && npm run build
```

#### 2. Claude Desktop 설정

`claude_desktop_config.json` 파일에 MCP 서버 추가:

```json
{
  "mcpServers": {
    "payment-circuit-breaker": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/circuit-breaker-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-database": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/database-query-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-redis": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/redis-cache-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-kafka": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/kafka-operations-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    }
  }
}
```

> **주의**: MCP 서버들은 monitoring-service API를 통해 동작합니다. Docker Compose로 시스템을 실행한 후 사용하세요.

#### 3. Claude Desktop 재시작

설정 파일 수정 후 Claude Desktop을 완전히 재시작하면 MCP 서버들이 자동으로 로드됩니다.

### 사용 시나리오

#### 시나리오 1: 결제 트러블슈팅

**문제**: "결제가 완료 안 됐는데 왜 그래?"

**Claude의 MCP 활용**:

1. Database MCP로 결제 상태 확인
2. Outbox 이벤트 발행 여부 확인
3. Circuit Breaker MCP로 Kafka 장애 확인
4. 결론: "오후 2시 Kafka 다운으로 이벤트 미발행"

#### 시나리오 2: 성능 저하 분석

**문제**: "API가 느린데 뭐가 문제야?"

**Claude의 MCP 활용**:

1. Circuit Breaker MCP로 Kafka 정상 확인
2. Redis MCP로 캐시 Hit Rate 확인 (30%, 평소 90%)
3. Database MCP로 트래픽 급증 확인
4. 결론: "Redis 캐시 만료로 DB 쿼리 증가"

#### 시나리오 3: Rate Limit 모니터링

**문제**: "특정 머천트가 429 에러를 받는다는데?"

**Claude의 MCP 활용**:

1. Redis MCP로 해당 머천트 Rate Limit 확인 (980/1000)
2. 다른 머천트들도 임계치 근접 확인
3. 권장: "정상 패턴, Rate Limit 증가 또는 재시도 로직 안내"

### MCP vs REST API

| 용도                | MCP 서버                          | monitoring-service REST API |
| ------------------- | --------------------------------- | --------------------------- |
| **AI 디버깅** | ✅ Claude Desktop 자연어 상호작용 | ❌                          |
| **로컬 개발** | ✅ 빠른 피드백                    | ✅ curl/Postman             |
| **팀 공유**   | ❌ 개인 환경                      | ✅ URL 공유                 |
| **CI/CD**     | ❌                                | ✅ Jenkins/GitHub Actions   |
| **Grafana**   | ❌                                | ✅ 메트릭 연동              |

#### MCP 동작 원리

1. **MCP 서버 실행**프로젝트 루트의 `.claude/mcp_settings.json`에 MCP 서버들의 실행 명령과 환경 변수가 정의되어 있습니다. Claude Desktop이 레포를 열면 이 설정을 읽고 `node mcp-servers/<name>/dist/index.js` 형태의 프로세스를 stdio 모드로 띄웁니다.
2. **툴 선언으로 기능 노출**각 서버는 `ListTools` 응답에서 사용할 수 있는 툴 이름, 설명, 입력 스키마를 반환합니다(예: `kafka-operations-mcp/src/index.ts`의 `getTools()` 함수). Claude는 이 설명을 기반으로 어떤 상황에 어떤 툴을 호출할지 판단합니다.
3. **자연어 → MCP 호출**사용자가 “DLQ 메시지 보여줘”처럼 자연어로 요청하면 Claude LLM이 적합한 MCP 툴을 선택해 `CallTool` 요청을 보냅니다. 예를 들어 `get_dlq_messages`를 호출하면서 `{"limit":10}` 같은 JSON 인자를 넘깁니다.
4. **백엔드 연동 및 응답**MCP 서버는 전달받은 인자를 기반으로 `axios` 등을 이용해 `monitoring-service`의 REST API나 Kafka/Redis/DB에 접근해 필요한 데이터를 가져옵니다. 그런 뒤 결과를 사람이 읽기 쉬운 문자열로 가공해 MCP 응답으로 돌려줍니다.
5. **Claude가 대화에 통합**
   Claude Desktop은 MCP 응답을 받아 자연어 문장으로 다시 정리해 채팅에 포함시킵니다. 사용자는 추가 맥락 없이도 “Kafka 토픽 상태”, “DB 미발행 이벤트”처럼 여러 운영 정보를 대화형으로 확인할 수 있습니다.

**권장**:

- 로컬 디버깅 → MCP 서버 사용
- 운영 모니터링 → REST API 사용

### 클라우드 배포 시 MCP 사용

#### 시나리오 1: KT 클라우드 배포 (추천)

**구성**:

```
로컬 PC (Claude Desktop + MCP)
    ↓ HTTP (포트 포워딩 또는 VPN)
KT 클라우드 (monitoring-service:8082)
```

**방법**:

1. **SSH 포트 포워딩** (가장 간단):

   ```bash
   ssh -L 8082:localhost:8082 user@kt-cloud-ip
   ```

   - 로컬 8082 포트가 클라우드의 monitoring-service:8082로 연결됨
   - MCP 설정 변경 불필요 (여전히 `http://localhost:8082` 사용)
2. **직접 접근** (보안 주의):

   ```json
   {
     "mcpServers": {
       "payment-circuit-breaker": {
         "env": {
           "API_BASE_URL": "http://kt-cloud-ip:8082"
         }
       }
     }
   }
   ```

   - KT 클라우드에서 8082 포트를 열어야 함
   - **보안**: IP 화이트리스트 또는 VPN 필수
3. **VPN 터널** (가장 안전):

   - KT 클라우드와 VPN 연결
   - 프라이빗 IP로 monitoring-service 접근
   - 보안 규정 준수

#### 시나리오 2: 클라우드에 Claude Desktop 설치 (비추천)

Claude Desktop을 클라우드 VM에 설치할 수도 있지만:

**단점**:

- 원격 데스크톱 필요 (GUI)
- 네트워크 지연
- 비용 증가 (VM 리소스)

**추천하지 않는 이유**:

- MCP는 로컬 개발/디버깅 도구
- 운영 모니터링은 Grafana 사용

#### 권장 아키텍처

```
┌─────────────────────────────────────────────┐
│          개발자 로컬 PC                       │
│                                             │
│  Claude Desktop + MCP Servers               │
│    ↓ (SSH 포트 포워딩)                       │
└─────────────────┬───────────────────────────┘
                  │
    ┌─────────────▼─────────────────────────┐
    │      KT 클라우드 (프로덕션)             │
    │                                       │
    │  monitoring-service:8082              │
    │    ↓ 데이터 조회                       │
    │  MariaDB, Redis, Kafka                │
    └───────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│        팀원/운영팀 (MCP 없음)                │
│                                             │
│  웹 브라우저                                 │
│    ↓                                        │
│  Grafana (http://kt-cloud-ip:3000)         │
└─────────────────────────────────────────────┘
```

#### 실전 사용법

**로컬 개발 시**:

```bash
# 로컬에서 Docker Compose로 실행
docker compose up -d

# Claude Desktop에서 MCP 사용
# API_BASE_URL=http://localhost:8082
```

**KT 클라우드 배포 후**:

```bash
# SSH 터널 연결
ssh -L 8082:localhost:8082 user@kt-cloud-ip

# Claude Desktop에서 동일하게 MCP 사용
# API_BASE_URL은 그대로 http://localhost:8082
```

**팀원/운영팀**:

```bash
# Grafana로 모니터링
http://kt-cloud-ip:3000

# 또는 monitoring-service REST API 직접 사용
curl http://kt-cloud-ip:8082/monitoring/circuit-breaker
```

#### 보안 고려사항

1. **SSH 터널**: 가장 안전, 별도 포트 오픈 불필요
2. **VPN**: 회사 정책에 따라 필수일 수 있음
3. **IP 화이트리스트**: 8082 포트를 열 경우 반드시 적용
4. **인증/인가**: monitoring-service에 Spring Security 추가 권장

#### 요약

| 환경                  | MCP 사용 | 접근 방법             | 비고                   |
| --------------------- | -------- | --------------------- | ---------------------- |
| **로컬 개발**   | ✅       | localhost:8082        | Docker Compose         |
| **KT 클라우드** | ✅       | SSH 포트 포워딩       | 추천 (보안)            |
| **KT 클라우드** | ✅       | 직접 접근 (포트 오픈) | 비추천 (보안 위험)     |
| **팀원/운영**   | ❌       | Grafana 또는 REST API | MCP 없이 모니터링 가능 |

## Frontend Studio (SWELITE Commerce LAB)

**React + Vite 기반 프리미엄 결제 체험 스튜디오**

### 주요 기능

- **프리미엄 제품 카탈로그**: Signature Tech (iPhone 16 Pro, Galaxy S25 Ultra, iPad Pro)와 Atelier Living (Dyson Gen5, Balmuda Air Purifier, Breville Barista) 카테고리로 구성된 고급 제품 라인업
- **완전한 결제 플로우**: 승인(Authorize) → 정산(Capture) → 환불(Refund) 전체 사이클을 단일 화면에서 시연
- **멱등성 키 관리**: 주문 제어 패널에서 멱등성 키를 직접 관리하고 중복 결제 방지 테스트 가능
- **실시간 영수증 타임라인**: 승인/정산/환불 단계가 시간순으로 시각화되어 결제 상태 추적 용이
- **프로덕션급 UI/UX**: 다크 테마, 글래스모피즘 효과, 반응형 그리드 레이아웃으로 상용 서비스 수준의 인터페이스 구현
- **Gateway 통합**: 모든 API 요청은 Spring Cloud Gateway(`http://localhost:8080/api`)를 통해 라우팅됨
- **관리자 대시보드**: 데이터베이스 조회, K6 부하 테스트 실행, AI 분석 보고서 생성을 웹 UI로 관리

### 접속 방법

```bash
# Docker Compose 실행 후
http://localhost:5173          # 고객용 결제 스튜디오
http://localhost:5173/admin    # 관리자 대시보드
```

### 관리자 대시보드 (Admin Dashboard)

**주요 기능**:
- **데이터베이스 조회**: Payment, Outbox, Settlement, Refund 테이블 실시간 조회
- **K6 부하 테스트**: 웹 UI에서 원클릭으로 승인 전용/승인+정산/전체 플로우 테스트 실행
- **AI 분석 보고서**: OpenAI GPT-4 기반 성능 분석 및 최적화 권장사항 생성
- **서킷 브레이커 테스트**: Kafka 장애 시나리오 자동 실행 및 검증

**사용 예시**:
```bash
# 데이터베이스 조회
curl http://localhost:8082/api/admin/query

# K6 부하 테스트 실행 (승인 전용)
curl -X POST http://localhost:8082/api/admin/loadtest/run \
  -H "Content-Type: application/json" \
  -d '{"scenario": "authorize-only"}'

# AI 분석 보고서 생성
curl http://localhost:8082/api/admin/loadtest/analyze
```

### 주요 파일

- [frontend/src/App.jsx](frontend/src/App.jsx): 메인 애플리케이션 컴포넌트 (988줄)
- [frontend/src/AdminPage.jsx](frontend/src/AdminPage.jsx): 관리자 대시보드 컴포넌트
- [frontend/src/styles.css](frontend/src/styles.css): 프로덕션급 스타일링 (653줄)

---

## MockPG 부하 테스트 모드

MockPG는 실제 PG사 API를 시뮬레이션하는 Mock 클라이언트로 두 가지 모드를 지원합니다.

### 모드별 실패율

| 모드                   | Authorization | Settlement | Refund | 용도               |
| ---------------------- | ------------- | ---------- | ------ | ------------------ |
| **일반 모드**    | 0.5%          | 5%         | 5%     | 에러 처리 로직 검증 |
| **부하 테스트 모드** | 0.01%         | 0.01%      | 0.01%  | 순수 성능 측정     |

### 설정 방법

```bash
# .env 파일
MOCK_PG_LOADTEST_MODE=true   # 부하 테스트 모드 (K6 테스트 시 권장)
MOCK_PG_LOADTEST_MODE=false  # 일반 모드 (개발/QA)
```

### 동작 원리

**일반 모드** (개발/QA):
```java
// 현실적인 실패율로 에러 처리 검증
double effectiveFailureRate = 0.005;  // 0.5% 실패율
if (Math.random() < effectiveFailureRate) {
    throw new PgApiException("PG_TIMEOUT", "승인 API 타임아웃");
}
```

**부하 테스트 모드** (성능 측정):
```java
// 실패율 최소화로 순수 성능 측정
double effectiveFailureRate = 0.0001;  // 0.01% 실패율
if (Math.random() < effectiveFailureRate) {
    log.warn("Mock PG failed (random failure): mode=LOADTEST");
    throw new PgApiException("PG_TIMEOUT", "승인 API 타임아웃");
}
```

### 사용 시나리오

**개발 환경** (일반 모드):
- 결제 실패 시 재시도 로직 검증
- Circuit Breaker 동작 확인
- DLQ 메시지 처리 테스트
- Outbox 재발행 로직 검증

**부하 테스트** (LOADTEST_MODE):
- K6 400 RPS 성능 테스트
- Threshold 통과 (http_req_failed < 5%, payment_errors < 2%)
- Circuit Breaker CLOSED 상태 유지
- 순수 시스템 처리 능력 측정

### K6 Threshold와의 관계

K6 부하 테스트는 다음 threshold를 검증합니다:

```javascript
const thresholds = {
  http_req_failed: ["rate<0.05"],      // HTTP 실패율 < 5%
  payment_errors: ["rate<0.02"],       // 결제 에러율 < 2%
  http_req_duration: ["p(95)<1000"],   // p95 응답시간 < 1초
};
```

**일반 모드 문제점**:
- Settlement/Refund 5% 실패율이 threshold 초과
- Circuit Breaker가 간헐적으로 OPEN 상태로 전환
- K6 테스트 결과: FAILED

**LOADTEST_MODE 해결책**:
- 모든 API 0.01% 실패율로 threshold 통과
- Circuit Breaker 안정적으로 CLOSED 유지
- K6 테스트 결과: PASSED

> **중요**: Threshold를 완화하는 것은 고객 경험을 희생하는 것입니다. 대신 MockPG 모드를 전환하여 목적에 맞는 테스트를 수행하세요.

