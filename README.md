# Payment_SWElite

## 주차별 목표

- **1주차**
  - React 목업 상점과 Spring Boot 기반 결제 API(승인·정산·환불)로 E2E 플로우 구현
  - Kafka, Redis, MariaDB, Jenkins를 포함한 Docker Compose 로컬 환경 구축
- **2주차**
  - [x] Redis를 활용한 rate limit 및 멱등 캐시 고도화
  - [ ] Prometheus + Grafana 지표 수집 및 시각화 파이프라인 구성
  - [ ] k6 부하/스트레스 테스트 시나리오 작성 및 Jenkins 리포트 자동화
  - [ ] Settlement/Reconciliation 서비스 테이블 추가 및 비동기 처리 확장
  - [x] payment.dlq 토픽으로 재전송하는 Consumer 예외 처리 보강

## 시스템 구성 요소

| 구성 | 설명 |
| --- | --- |
| **frontend** | React + Vite로 작성된 목업 상점 UI. iPhone 16 Pro, Galaxy S25 Ultra, Xiaomi 14T Pro를 대상으로 테스트 시나리오 제공. |
| **ingest-service** | Spring Boot(Java 21) 기반 결제 API. 승인/정산/환불 처리와 outbox 이벤트 발행 담당. |
| **consumer-worker** | Kafka Consumer. 결제 이벤트를 수신해 ledger 데이터를 보강하고 후속 처리를 준비. |
| **mariadb** | 메인 데이터베이스(paydb). payment, ledger_entry, outbox_event, idem_response_cache 테이블을 관리. |
| **kafka & zookeeper** | 결제 이벤트 토픽(`payment.authorized`, `payment.captured`, `payment.refunded`)을 호스팅. |
| **redis** | rate limit과 멱등 응답 캐시를 담는 in-memory 스토리지. |
| **jenkins** | Gradle + NPM 빌드와 Docker Compose 배포를 자동화하는 CI 서버. |

## 주요 테이블 DDL

`backend/ingest-service/src/main/resources/schema.sql`에서 확인할 수 있는 핵심 테이블은 다음과 같습니다.

- `payment` : 결제 상태 및 멱등 키 관리
- `ledger_entry` : 승인/정산/환불 시 생성되는 원장 데이터
- `outbox_event` : Kafka 발행 대기 이벤트 저장
- `idem_response_cache` : 승인 응답 멱등 캐시

## REST API 요약

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/payments/authorize` | 멱등성을 보장하는 승인을 처리하고 `payment` 및 `outbox_event`에 기록 |
| `POST` | `/payments/capture/{paymentId}` | 승인된 결제를 정산 처리, ledger 기록, 이벤트 발행 |
| `POST` | `/payments/refund/{paymentId}` | 정산된 결제를 환불 처리, ledger 기록, 이벤트 발행 |

오류 응답은 요구사항에 맞춰 `DUPLICATE_REQUEST`, `CAPTURE_CONFLICT`, `REFUND_CONFLICT`, `NOT_FOUND` 코드를 반환합니다.

## Kafka 토픽

- `payment.authorized`
- `payment.captured`
- `payment.refunded`
- `payment.dlq` (Consumer 실패 시 재전송)

## Redis 기반 보호 기능

- 승인 API 응답은 Redis TTL 캐시에 동시 저장돼 중복 요청 시 빠르게 재사용됩니다. 기본 TTL은 600초이며 `APP_IDEMPOTENCY_CACHE_TTL_SECONDS` 환경 변수로 조정할 수 있습니다.
- 상점(`merchantId`) 단위 승인·정산·환불 API는 분당 20/40/20회 레이트 리밋이 적용됩니다. `APP_RATE_LIMIT_*` 환경 변수로 조정 가능하고, Redis 장애 시 제한 없이 처리하도록 fail-open으로 구성했습니다.

## 로컬 실행 방법

1. **Docker Compose 기동**
   ```bash
   docker compose up --build
   ```
   MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend가 함께 기동됩니다.

2. **프런트엔드 접속**
   - http://localhost:5173
   - 상품, 색상, 수량을 선택하고 결제 테스트 수행
   - 성공 시 authorize + capture 응답 JSON 확인

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

4. **서비스 종료**
   ```bash
   docker compose down
   ```

## Jenkins 파이프라인

`Jenkinsfile`은 다음 단계를 자동화합니다.

1. 소스 체크아웃
2. `frontend` 빌드 (npm install + Vite build)
3. `backend` 빌드 (Gradle clean build)
4. Docker 이미지 빌드 및 Compose 기동
5. 간단한 `curl` 기반 Smoke Test
6. 파이프라인 종료 후 Compose 정리

## 다음 단계 제안

- Prometheus + Grafana 지표 수집/시각화
- k6 부하/스트레스 테스트 시나리오 및 Jenkins 리포트 자동화
- Settlement/Reconciliation 서비스 테이블 추가 및 비동기 처리 확장
