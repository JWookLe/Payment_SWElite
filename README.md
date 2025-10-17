# Payment_SWElite

## 주차별 목표
- **1주차**
  - React 목업 스토어와 Spring Boot 기반 결제 API(승인/정산/환불)로 E2E 흐름 구현
  - Kafka, Redis, MariaDB, Jenkins가 포함된 Docker Compose 로컬 환경 구축
- **2주차**
  - [x] Redis 기반 rate limit 및 멱등 캐시 고도화
  - [x] Prometheus + Grafana 지표 수집 및 시각화 파이프라인 구성
  - [ ] k6 부하/스트레스 테스트 시나리오 작성 및 Jenkins 리포트 자동화
  - [ ] Settlement/Reconciliation 대비 비동기 처리 보강
  - [x] payment.dlq 토픽 재전송 기반 Consumer 예외 처리 보강

## 서비스 구성 요소
| 구성 | 설명 |
| --- | --- |
| **frontend** | React + Vite로 작성된 목업 스토어 UI. iPhone / Galaxy 등 주요 단말 결제 시나리오 제공. |
| **ingest-service** | Spring Boot(Java 21) 기반 결제 API. 승인/정산/환불 처리와 outbox 이벤트 발행 담당. |
| **consumer-worker** | Kafka Consumer. 결제 이벤트를 ledger 엔트리로 반영하고 DLQ 처리 로직 포함. |
| **mariadb** | paydb 스키마 운영. payment, ledger_entry, outbox_event, idem_response_cache 테이블 관리. |
| **kafka & zookeeper** | 결제 이벤트 토픽(`payment.authorized`, `payment.captured`, `payment.refunded`)을 호스팅. |
| **redis** | rate limit 카운터 및 결제 승인 응답 멱등 캐시 저장. |
| **jenkins** | CI 서버. Gradle/NPM 빌드, Docker Compose 배포, k6 부하 테스트 자동화. |
| **prometheus/grafana** | 애플리케이션 메트릭 수집 및 대시보드 제공. |

## 주요 데이터베이스 DDL
`backend/ingest-service/src/main/resources/schema.sql` 참고
- `payment`: 결제 상태 및 멱등 키 보관
- `ledger_entry`: 승인/정산/환불 시 생성되는 회계 분개 기록
- `outbox_event`: Kafka 발행 전 이벤트 저장소
- `idem_response_cache`: 결제 승인 응답 멱등 캐시

## REST API 요약
| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/payments/authorize` | 멱등 키 기반 결제 승인 처리 및 outbox 기록 |
| `POST` | `/payments/capture/{paymentId}` | 승인된 결제 정산 처리, ledger 기록, 이벤트 발행 |
| `POST` | `/payments/refund/{paymentId}` | 정산 완료 결제 환불 처리, ledger 기록, 이벤트 발행 |

## Kafka 토픽
- `payment.authorized`
- `payment.captured`
- `payment.refunded`
- `payment.dlq`

## Redis 기반 보호 기능
- 승인 API 응답을 Redis TTL 캐시에 저장하여 멱등성을 보장합니다. 기본 TTL은 600초 (`APP_IDEMPOTENCY_CACHE_TTL_SECONDS`로 조정 가능).
- 가맹점(`merchantId`)별 승인·정산·환불 API에 분당 20/40/20 회 rate limit이 적용됩니다. `APP_RATE_LIMIT_*` 환경 변수로 조정 가능하며, Redis 장애 시 fail-open 전략을 사용합니다.
- Docker Compose 환경에서는 부하 테스트 편의를 위해 승인/정산 1000, 환불 500으로 확장돼 있습니다. 필요 시 `APP_RATE_LIMIT_*` 값을 조정해 운영 환경에 맞춰 주세요.

## Observability (Prometheus & Grafana)
- `docker compose up -d` 시 Prometheus(9090)와 Grafana(3000)가 함께 기동됩니다.
- Grafana 기본 계정: `admin`/`admin` (첫 로그인 후 비밀번호 변경 권장)
- `Payment Service Overview` 대시보드에서 요청 속도, p95 지연시간, Kafka 소비량, 에러율 등을 확인할 수 있습니다.

## Load Testing (k6)
- `loadtest/k6/payment-scenario.js`는 승인 → 정산 → (선택적) 환불 흐름을 검증하며, 환경 변수로 각 단계를 토글할 수 있습니다.
- 기본 설정은 초당 200건까지 ramp-up 하며, `BASE_URL`, `MERCHANT_ID` 환경 변수로 수정 가능합니다.
- 기본 실행은 승인(Authorize) API만 대상으로 하며, 정산/환불은 필요 시 `ENABLE_CAPTURE=true`, `ENABLE_REFUND=true` 환경 변수로 개별 활성화할 수 있습니다.
- 승인과 후속 처리를 분리하면 승인 API를 빠르게 튜닝하고, 정산/환불은 비동기 처리나 배치 등 별도 전략으로 확장할 수 있습니다.
- 로컬 실행 예시:
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm --network payment_swelite_default \
    -v "$PWD/loadtest/k6":/k6 \
    -e BASE_URL=http://ingest-service:8080 \
    -e MERCHANT_ID=LOCAL \
    grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
  ```
- Jenkins 파이프라인에서는 승인 전용 시나리오로 k6를 실행하고 요약 JSON을 아카이브합니다.

## 로컬 실행 방법
1. `docker compose up --build`
   - MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend, Prometheus, Grafana 기동
2. 프런트엔드 접속: http://localhost:5173
3. API 확인 예시:
   ```bash
   curl -X POST http://localhost:8080/payments/authorize \
     -H 'Content-Type: application/json' \
     -d '{
       "merchantId":"M123",
       "amount":10000,
       "currency":"KRW",
       "idempotencyKey":"abc-123"
     }'
   ```
4. 종료: `docker compose down`

## Jenkins 파이프라인 개요
1. 소스 체크아웃
2. 프런트엔드 빌드 (npm install + vite build)
3. 백엔드 빌드 (Gradle build)
4. Docker Compose 기동 (Prometheus/Grafana 포함)
5. Smoke Test / k6 Load Test 실행 → 요약 저장
6. 파이프라인 종료 시 docker compose down

## 향후 계획
- k6 부하 테스트 결과 리포트 자동화 마무리
- Settlement/Reconciliation 대비 비동기 처리 보강
- 추가 대시보드/알람 구성 및 운영 안정화