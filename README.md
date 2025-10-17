# Payment_SWElite

## 주차�?목표

- Docker Compose ȯ�濡���� �׽�Ʈ ���Ǹ� ���� ����/���� �뷮�� 1000, ȯ���� 500���� Ȯ���� �ξ����ϴ�. �ʿ� �� `APP_RATE_LIMIT_*` ���� ������ � ȯ�濡 ���� �� �ֽ��ϴ�.
- **1주차**
  - React 목업 ?�점�?Spring Boot 기반 결제 API(?�인·?�산·?�불)�?E2E ?�로??구현
  - Kafka, Redis, MariaDB, Jenkins�??�함??Docker Compose 로컬 ?�경 구축
- **2주차**
  - [X] Redis�??�용??rate limit �?멱등 캐시 고도??  - [ ] Prometheus + Grafana 지???�집 �??�각???�이?�라??구성
  - [ ] k6 부???�트?�스 ?�스???�나리오 ?�성 �?Jenkins 리포???�동??  - [ ] Settlement/Reconciliation ?�비???�이�?추�? �?비동�?처리 ?�장
  - [X] payment.dlq ?�픽?�로 ?�전?�하??Consumer ?�외 처리 보강

## ?�스??구성 ?�소

| 구성                        | ?�명                                                                                                                 |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **frontend**          | React + Vite�??�성??목업 ?�점 UI. iPhone 16 Pro, Galaxy S25 Ultra, Xiaomi 14T Pro�??�?�으�??�스???�나리오 ?�공. |
| **ingest-service**    | Spring Boot(Java 21) 기반 결제 API. ?�인/?�산/?�불 처리?� outbox ?�벤??발행 ?�당.                                   |
| **consumer-worker**   | Kafka Consumer. 결제 ?�벤?��? ?�신??ledger ?�이?��? 보강?�고 ?�속 처리�?준�?                                      |
| **mariadb**           | 메인 ?�이?�베?�스(paydb). payment, ledger_entry, outbox_event, idem_response_cache ?�이블을 관�?                    |
| **kafka & zookeeper** | 결제 ?�벤???�픽(`payment.authorized`, `payment.captured`, `payment.refunded`)???�스??                       |
| **redis**             | rate limit�?멱등 ?�답 캐시�??�는 in-memory ?�토리�?.                                                               |
| **jenkins**           | Gradle + NPM 빌드?� Docker Compose 배포�??�동?�하??CI ?�버.                                                        |

## 주요 ?�이�?DDL

`backend/ingest-service/src/main/resources/schema.sql`?�서 ?�인?????�는 ?�심 ?�이블�? ?�음�?같습?�다.

- `payment` : 결제 ?�태 �?멱등 ??관�?- `ledger_entry` : ?�인/?�산/?�불 ???�성?�는 ?�장 ?�이??- `outbox_event` : Kafka 발행 ?��??�벤???�??- `idem_response_cache` : ?�인 ?�답 멱등 캐시

## REST API ?�약

| Method   | Path                              | ?�명                                                                     |
| -------- | --------------------------------- | ------------------------------------------------------------------------ |
| `POST` | `/payments/authorize`           | 멱등?�을 보장?�는 ?�인??처리?�고 `payment` �?`outbox_event`??기록 |
| `POST` | `/payments/capture/{paymentId}` | ?�인??결제�??�산 처리, ledger 기록, ?�벤??발행                        |
| `POST` | `/payments/refund/{paymentId}`  | ?�산??결제�??�불 처리, ledger 기록, ?�벤??발행                        |

?�류 ?�답?� ?�구?�항??맞춰 `DUPLICATE_REQUEST`, `CAPTURE_CONFLICT`, `REFUND_CONFLICT`, `NOT_FOUND` 코드�?반환?�니??

## Kafka ?�픽

- `payment.authorized`
- `payment.captured`
- `payment.refunded`
- `payment.dlq` (Consumer ?�패 ???�전??

## Redis 기반 보호 기능

- ?�인 API ?�답?� Redis TTL 캐시???�시 ?�?�돼 중복 ?�청 ??빠르�??�사?�됩?�다. 기본 TTL?� 600초이�?`APP_IDEMPOTENCY_CACHE_TTL_SECONDS` ?�경 변?�로 조정?????�습?�다.
- ?�점(`merchantId`) ?�위 ?�인·?�산·?�불 API??분당 20/40/20???�이??리밋???�용?�니?? `APP_RATE_LIMIT_*` ?�경 변?�로 조정 가?�하�? Redis ?�애 ???�한 ?�이 처리?�도�?fail-open?�로 구성?�습?�다.

## 로컬 ?�행 방법

1. **Docker Compose 기동**

   ```bash
   docker compose up --build
   ```

   MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend가 ?�께 기동?�니??
2. **?�런?�엔???�속**

   - http://localhost:5173
   - ?�품, ?�상, ?�량???�택?�고 결제 ?�스???�행
   - ?�공 ??authorize + capture ?�답 JSON ?�인
3. **?�동 API ?�스??*

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
4. **?�비??종료**

   ```bash
   docker compose down
   ```

## Jenkins ?�이?�라??
`Jenkinsfile`?� ?�음 ?�계�??�동?�합?�다.

1. ?�스 체크?�웃
2. `frontend` 빌드 (npm install + Vite build)
3. `backend` 빌드 (Gradle clean build)
4. Docker ?��?지 빌드 �?Compose 기동
5. 간단??`curl` 기반 Smoke Test
6. ?�이?�라??종료 ??Compose ?�리

## ?�음 ?�계 ?�안

- Prometheus + Grafana 지???�집/?�각??- k6 부???�트?�스 ?�스???�나리오 �?Jenkins 리포???�동??- Settlement/Reconciliation ?�비???�이�?추�? �?비동�?처리 ?�장
