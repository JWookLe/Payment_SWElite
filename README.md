# Payment_SWElite

## 주차별 목표
- **1주차**
  - React 목업 스토어와 Spring Boot 기반 결제 API(승인/정산/환불)로 E2E 흐름 구현
  - Kafka, Redis, MariaDB, Jenkins가 포함된 Docker Compose 개발 환경 구축
- **2주차**
  - [x] Redis 기반 rate limit 및 멱등 캐시 고도화
  - [x] Prometheus + Grafana 지표 수집 및 시각화 파이프라인 구성
  - [ ] k6 부하/스트레스 테스트 시나리오 작성 및 Jenkins 리포트 자동화
  - [ ] Settlement/Reconciliation 대비 비동기 처리 보강
  - [x] payment.dlq 토픽 재전송 기반 Consumer 예외 처리 보강

## 서비스 구성 요소
| 구성 | 설명 |
| --- | --- |
| **frontend** | React + Vite로 작성된 목업 스토어 UI. 아이폰/갤럭시 등 주요 단말 결제 시나리오 제공. |
| **ingest-service** | Spring Boot(Java 21) 결제 API. 승인/정산/환불 처리와 outbox 이벤트 발행 담당. |
| **consumer-worker** | Kafka Consumer. 결제 이벤트를 ledger 엔트리로 반영하고 DLQ 전송 로직을 포함. |
| **mariadb** | 메인 데이터베이스(paydb). payment, ledger_entry, outbox_event, idem_response_cache 테이블 관리. |
| **kafka & zookeeper** | 결제 도메인 이벤트 토픽(`payment.authorized`, `payment.captured`, `payment.refunded`)을 호스팅. |
| **redis** | rate limit 카운터 및 결제 승인 응답 멱등 캐시를 저장하는 인메모리 스토어. |
| **jenkins** | Gradle + NPM 빌드와 Docker Compose 배포, 부하 테스트를 자동화하는 CI 서버. |
| **prometheus/grafana** | 애플리케이션 메트릭 수집 및 모니터링 대시보드 제공. |

## 주요 데이터베이스 DDL
`backend/ingest-service/src/main/resources/schema.sql`에서 확인할 수 있습니다.
- `payment`: 결제 상태와 멱등 키 보관
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
- `payment.dlq` (Consumer 예외 발생 시 재전송)

## Redis 기반 보호 기능
- 승인 API 응답을 Redis TTL 캐시에 보관하여 멱등성을 보장합니다. 기본 TTL은 600초이며 `APP_IDEMPOTENCY_CACHE_TTL_SECONDS`로 조정할 수 있습니다.
- 가맹점(`merchantId`) 별로 승인/정산/환불 API에 분당 20/40/20 회 rate limit이 적용됩니다. `APP_RATE_LIMIT_*` 환경 변수를 통해 조정 가능하며, Redis 장애 시 fail-open 전략을 사용합니다.

## Observability (Prometheus & Grafana)
- `docker compose` 실행 시 Prometheus와 Grafana가 자동 기동됩니다.
- Prometheus: http://localhost:9090 (ingest-service & consumer-worker의 `/actuator/prometheus` 스크랩)
- Grafana: http://localhost:3000 (기본 계정 `admin`/`admin`, 최초 로그인 후 비밀번호 변경 권장)
- Grafana에는 `Payment Service Overview` 대시보드가 기본 제공되어 API 요청 속도, p95 지연시간, Kafka Consumer 처리량 등을 확인할 수 있습니다.

## Load Testing (k6)
- `loadtest/k6/payment-scenario.js` 시나리오는 승인 → 정산 → (선택적) 환불 흐름을 한 번에 검증합니다.
- 기본 설정은 1분 동안 초당 10건으로 시작하여 최대 초당 30건까지 ramp-up 됩니다. `BASE_URL`, `MERCHANT_ID` 환경 변수를 덮어써서 다른 환경으로 실행할 수 있습니다.
- 로컬 실행 예시:
  ```bash
  k6 run loadtest/k6/payment-scenario.js
  ```
- Jenkins 파이프라인에서 자동으로 실행되며 `loadtest/k6/summary.json` 아카이브로 결과를 확인할 수 있습니다.

## 로컬 실행 방법
1. **Docker Compose 기동**
   ```bash
   docker compose up --build
   ```
   MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend, Prometheus, Grafana가 함께 기동됩니다.

2. **프런트엔드 접속**
   - http://localhost:5173
   - 상품, 색상, 수량을 선택 후 결제 플로우 수행
   - 승인/정산 결과와 멱등 키 동작을 UI에서 확인

3. **수동 API 테스트**
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

4. **종료**
   ```bash
   docker compose down
   ```

## Jenkins 파이프라인 개요
1. 소스 체크아웃
2. 프런트엔드 빌드 (npm install + Vite build)
3. 백엔드 빌드 (Gradle build)
4. Docker Compose로 서비스 기동 (Prometheus, Grafana 포함)
5. Smoke Test로 결제 승인 API 검증
6. k6 Load Test 실행 후 요약 리포트 아카이브
7. 파이프라인 종료 시 docker compose down

## 향후 계획
- k6 부하 테스트 결과를 바탕으로 Jenkins 리포트 자동화 마무리
- Settlement/Reconciliation 대비 비동기 처리 보강
- 추가 대시보드/알람 구성 및 운영 안정화
