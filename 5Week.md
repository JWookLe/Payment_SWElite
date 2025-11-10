# 5주차 작업

## 0. 주간 목표

- Docker 이미지/컨텍스트 최적화로 CI 시간을 15분 → 6분대로 단축
- Jenkins 파이프라인 안정화 및 자동 헬스체크·스모크 테스트 도입
- 승인 → 정산 → 환불 워커 아키텍처 일관성 확보
- 운영 편의 기능(HeidiSQL 접속, 포트 충돌) 정비

---

## 1. CI/CD 안정화

### 1-1. Docker 컨텍스트 경량화

| 변경 항목 | 설명 |
| --- | --- |
| `.dockerignore` | `backend/*/build`만 허용하고 나머지는 제외 → 컨텍스트 180MB → 26MB |
| `frontend/.dockerignore` | `node_modules`, `dist` 차단 |
| `frontend/Dockerfile` | `npm ci`, 전용 컨텍스트(`./frontend`), ARG 기반 Vite 설정 |
| backend Dockerfile 전부 | 공통 패턴 적용 (ARG `build/libs/*.jar`, 비루트 `spring` 사용자, `curl` 설치) |

> 덕분에 Jenkins Build 단계의 Docker build 시간이 8m 12s → 2m 45s.

### 1-2. Jenkins 파이프라인 리빌드

- `tools` 섹션 정리 후 전 단계 `npm ci`, `gradlew bootJar` → 구조 동일.
- `curl-client`(curlimages/curl)를 Compose 프로필로 추가해 컨테이너 밖에서 헬스체크/스모크 테스트 수행.
- `Wait for Services` + `Smoke Test`에서 각자 `docker compose run --rm --no-deps curl-client "curl …"` 형태로 재시도.
- `Circuit Breaker Test` 단계는 기존 스크립트 재사용.

```groovy
check_ready http://ingest-service:8080/actuator/health ingest-service
docker compose run --rm --no-deps curl-client \
  "curl -sSf -X POST ... http://gateway:8080/api/payments/authorize > /dev/null"
```

---

## 2. 이벤트 파이프라인 정렬

### 2-1. 승인 → 정산 비동기 파이프라인

- `PaymentService`가 승인 성공 시 `PAYMENT_AUTHORIZED`와 동시에 `PAYMENT_CAPTURE_REQUESTED` 이벤트를 Outbox로 기록.
- 정산 워커는 기존 `payment.capture-requested` 구독 로직을 그대로 재사용하여 완전한 비동기 플로우 완성.

```java
payment.setStatus(PaymentStatus.CAPTURE_REQUESTED);
publishEvent(payment, "PAYMENT_CAPTURE_REQUESTED", Map.of(
    "paymentId", payment.getId(),
    "approvalNumber", pgResponse.getApprovalNumber(),
    ...
));
```

### 2-2. Outbox 스케줄러/Publisher 일원화

- 토픽 매핑을 `PaymentEventPublisher.resolveTopicName()`에서 단일 관리.
- 스케줄러는 동일 메서드 재사용 → 새로운 이벤트 타입 추가 시 한 곳만 수정하면 됨.

### 2-3. consumer-worker 경량화

- 사용되지 않던 `Payment`/`PaymentStatus` 엔티티와 Repository 삭제.
- `payment.authorized` 구독 제거 → ledger에 영향이 있는 `captured/refunded`만 처리.
- DLQ 전송 시 `kafkaTemplate.send(...).get()`으로 실패 감지, 실패 시 예외 throw.
- 단위 테스트도 Future 기반으로 업데이트.

### 2-4. refund-worker 신뢰성

- 중복 요청 / 재시도 대비 로직 추가:
  - 이미 `REFUND_REQUESTED`가 성공했으면 상태만 확인 후 이벤트 재발행.
  - `existsByPaymentIdAndStatus(...SUCCESS)`로 멱등성 확보.
- Kafka 발행도 `send().get()`으로 동기화, 실패 시 예외 발생 → Jenkins/모니터링에서 감지 가능.

---

## 3. 운영 편의 개선

### 3-1. MariaDB 포트 조정

- 로컬 `mysqld.exe`와 3306 충돌 → Compose에서 `13306:3306`으로 노출.
- Docker 재배포 후 HeidiSQL 접속 가이드 문서화.

```
Host  : 127.0.0.1
Port  : 13306
User  : payuser
Pass  : paypass
DB    : paydb
```

### 3-2. Jenkins/Ngrok 제외 빌드 가이드

- `docker compose stop mariadb && docker compose rm -f mariadb && docker compose up -d mariadb`
- Jenkins/Ngrok 컨테이너는 유지하면서 필요한 서비스만 선택 기동.

---

## 4. 테스트 & 검증

| 명령 | 목적 | 결과 |
| --- | --- | --- |
| `./gradlew :backend:ingest-service:test --tests ...PaymentServiceTest` | 승인 이벤트 발행 2건 검증 | PASS |
| `./gradlew :backend:consumer-worker:test` | DLQ/ledger 처리 테스트 | PASS |
| `docker exec pay-mariadb mariadb -upayuser -ppaypass -e "SELECT 1"` | DB 계정 확인 | PASS |
| Jenkins 파이프라인 | npm ci → gradle → docker build → compose up → 헬스체크/스모크 | PASS |

---

## 5. 다음 단계

1. refund-worker에도 Outbox 재시도 패턴 도입 (현재는 Kafka 동기 전송만 적용).
2. 이벤트 payload 스키마(Avro/JSON Schema) 표준화로 소비자 간 계약 명시.
3. Jenkins stage 병렬화(frontend/npm ↔ backend/gradle)로 추가 시간 단축.
4. Grafana에 워커별 DLQ 지표 패널 추가.
