# Payment_SWElite

1주차 목표는 React 목업 상점에서 결제 API를 호출하고, Kafka를 통해 Consumer Worker까지 연결한 뒤 MariaDB에 데이터를 적재하는 **End-to-End** 경로를 완성하는 것이다. 로컬 환경에서 Docker Compose와 Jenkins를 활용해 자동화 배포까지 시험할 수 있도록 구성했다. 모든 코드는 `main` 브랜치 기준으로 유지하며 별도의 작업 브랜치를 사용하지 않아도 된다.

## 시스템 구성 요소

| 구성 | 설명 |
| --- | --- |
| **frontend** | React + Vite로 구현된 목업 상점 UI. 아이폰 16 Pro, 갤럭시 S25 Ultra, 샤오미 14T Pro 상품을 선택하고 결제를 시도한다. |
| **ingest-service** | Spring Boot(Java 21) 기반 Ingest API. 결제 승인/매입/환불을 처리하고 outbox 이벤트를 Kafka로 발행한다. |
| **consumer-worker** | Kafka Consumer. 결제 이벤트를 수신하여 원장(ledger) 적재를 보강하고 향후 Redis 캐시 확장에 대비한다. |
| **mariadb** | 결제 도메인의 메인 데이터베이스(paydb). 4개 테이블(payment, ledger_entry, outbox_event, idem_response_cache) DDL을 적용했다. |
| **kafka & zookeeper** | 결제 이벤트 토픽(`payment.authorized`, `payment.captured`, `payment.refunded`) 처리. |
| **redis** | 추후 rate-limit, 멱등 캐시 고도화를 위한 in-memory 저장소. 현재 구성만 포함. |
| **jenkins** | Gradle + NPM 빌드, Docker 이미지 빌드/배포 파이프라인 자동화를 위한 CI 서버. |

## 주요 테이블 DDL

`backend/ingest-service/src/main/resources/schema.sql`에 제공된 스키마는 아래 4개 테이블을 초기화한다.

- `payment`: 결제 단건 상태 및 멱등성 키 관리
- `ledger_entry`: 이중부기 방식으로 자금 이동 내역 기록
- `outbox_event`: Kafka 발행 전에 트랜잭션 내 이벤트 적재
- `idem_response_cache`: 멱등 응답 캐시 저장(merchant + idempotency key 복합 PK)

## REST API 요약

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/payments/authorize` | 멱등성 검증 후 결제 승인 요청 처리, `payment` + `outbox_event` 기록 |
| `POST` | `/payments/capture/{paymentId}` | 승인 건 매입 처리, 상태를 `COMPLETED`로 변경하고 ledger 생성, 이벤트 발행 |
| `POST` | `/payments/refund/{paymentId}` | 매입 건 환불 처리, 역원장 기록 및 이벤트 발행 |

API 응답과 오류 포맷은 요구사항에 맞춰 `DUPLICATE_REQUEST`, `CAPTURE_CONFLICT`, `REFUND_CONFLICT`, `NOT_FOUND` 코드로 제공된다.

## Kafka 토픽

- `payment.authorized`
- `payment.captured`
- `payment.refunded`

Spring Kafka가 기동 시 자동으로 토픽을 생성한다. Consumer Worker는 매입/환불 이벤트를 수신해 ledger 보강을 수행한다.

## 로컬 실행 방법

1. **Docker Compose 기동**
   ```bash
   docker compose up --build
   ```
   mariadb/redis/kafka → ingest-service → consumer-worker → frontend 순으로 기동된다.

2. **프런트엔드 접속**
   - http://localhost:5173 접속
   - 상품, 색상, 수량 선택 후 “선택 상품 결제하기” 실행
   - 성공 시 authorize + capture 응답 JSON 확인 가능

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

`Jenkinsfile`은 다음 단계를 자동화한다.

1. 저장소 체크아웃
2. `frontend` 빌드 (NPM install + Vite build)
3. `backend` 빌드 (Gradle clean build)
4. Docker 이미지 빌드 및 Compose로 서비스 기동
5. 간단한 `curl` 기반 Smoke Test
6. 파이프라인 종료 후 Compose 정리

## 다음 단계 제안

- Redis를 활용한 rate limit 및 멱등 캐시 고도화
- Prometheus + Grafana 지표 수집/시각화
- k6 부하/스트레스 테스트 시나리오 작성 및 Jenkins 리포트 자동화
- Settlement/Reconciliation 서비스 테이블 추가 및 비동기 처리 확장

