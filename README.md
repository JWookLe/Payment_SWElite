# Payment_SWElite

## 주차별 목표

### 1주차
- React 목업 스토어와 Spring Boot 기반 결제 API(승인/정산/환불)로 E2E 흐름 구현
- Kafka, Redis, MariaDB, Jenkins가 포함된 Docker Compose 로컬 환경 구축

### 2주차
- [x] Redis 기반 rate limit 및 멱등 캐시 고도화
- [x] Prometheus + Grafana 지표 수집 및 시각화 파이프라인 구성
- [x] k6 부하/스트레스 테스트 시나리오 작성 및 200 RPS 목표 달성
- [x] GitHub Webhook + Jenkins 자동 빌드 파이프라인 구성
- [ ] Settlement/Reconciliation 대비 비동기 처리 보강
- [x] payment.dlq 토픽 재전송 기반 Consumer 예외 처리 보강

### 3주차 (현재)
- [x] Resilience4j 기반 Circuit Breaker 구현 (Kafka Publisher 보호)
- [x] Circuit Breaker 자동 테스트 및 모니터링 (9단계 시나리오)
- [x] Grafana에 Circuit Breaker 패널 추가 (4개 패널)
- [x] Jenkins 파이프라인에 Circuit Breaker Test 단계 통합
- [x] Circuit Breaker 완벽 가이드 문서화 (한국어)
- [ ] API Gateway 도입 (Spring Cloud Gateway)
- [ ] Service Mesh 검토 (Istio 또는 Linkerd)

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
- 승인 API 응답을 Redis TTL 캐시에 저장해서 멱등성을 보장함. 기본 TTL은 600초 (`APP_IDEMPOTENCY_CACHE_TTL_SECONDS`로 조정 가능).
- 가맹점(`merchantId`)별 승인·정산·환불 API에 Rate Limit이 적용됨. `APP_RATE_LIMIT_*` 환경 변수로 조정 가능하고, Redis 장애 시 fail-open 전략을 사용함.

### 성능 목표별 Rate Limit 설정
| 환경 | 목표 RPS | Rate Limit (분) | 비고 |
|------|----------|----------------|------|
| **개발** | ~10 RPS | 1,000/1,000/500 | 빠른 피드백 |
| **부하 테스트** | 200 RPS | 15,000/15,000/7,500 | 현재 설정 |
| **운영 (목표)** | 1,000 TPS | 70,000/70,000/35,000 | 최종 목표 |

## Observability (Prometheus & Grafana)

### 설정 및 접속
- `docker compose up -d` 시 Prometheus(9090)와 Grafana(3000)가 함께 기동됨.
- **Prometheus**: http://localhost:9090
  - Status → Targets에서 ingest-service, consumer-worker 메트릭 수집 상태 확인
- **Grafana**: http://localhost:3000
  - 기본 계정: `admin`/`admin`
  - `Payment Service Overview` 대시보드에서 요청 속도, p95 지연시간, Kafka 소비량, 에러율 등을 확인

### 구성 방식
- **Prometheus**: 커스텀 이미지 빌드 (`monitoring/prometheus/Dockerfile`)
  - 설정 파일을 이미지에 포함해서 볼륨 마운트 문제 해결
- **Grafana**: 커스텀 이미지 빌드 (`monitoring/grafana/Dockerfile`)
  - Datasource, Dashboard provisioning 설정을 이미지에 포함
  - Payment Service Overview 대시보드 자동 로드

## Load Testing (k6)

### 테스트 시나리오
`loadtest/k6/payment-scenario.js`는 승인 → 정산 → (선택적) 환불 흐름을 검증함. 환경 변수로 각 단계를 토글할 수 있음.

**현재 설정** (200 RPS 목표):
- Warm-up: 50 RPS (30초)
- Ramp-up: 50 → 100 → 150 → 200 RPS (5분)
- Sustain: 200 RPS (2분)
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
-  **에러율**: < 1%
-  **p95 응답시간**: < 100ms
-  **처리량**: 200 RPS 안정적 처리

## 로컬 실행 방법
1. `docker compose up --build`
   - MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend, Prometheus, Grafana를 기동함
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

## Circuit Breaker (Resilience4j)

Kafka 발행 실패로부터 시스템을 보호하는 프로덕션 수준의 Circuit Breaker 구현임.

### 개요
- **프레임워크**: Resilience4j 2.1.0
- **보호 대상**: Kafka Publisher (ingest-service)
- **상태 관리**: CLOSED → OPEN → HALF_OPEN → CLOSED
- **자동 복구**: 의존성 회복 시 자동으로 서비스 복구

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

### 실시간 모니터링
- **Prometheus**: http://localhost:9090 → 쿼리 검색 → `resilience4j_circuitbreaker_state`
- **Grafana**: http://localhost:3000 → Dashboards → "Payment Service Overview" → Circuit Breaker 패널

### 상태별 동작
| 상태 | 의미 | 동작 |
|------|------|------|
| **CLOSED (0)** | 정상 | 모든 요청 통과, 메트릭 기록 |
| **OPEN (1)** | 장애 감지 | 요청 즉시 차단 (30초 대기) |
| **HALF_OPEN (2)** | 복구 시도 | 제한된 요청으로 상태 확인 (최대 3개) |
| **DISABLED (3)** | 비활성화 | 항상 요청 통과, 메트릭만 기록 |
| **FORCED_OPEN (4)** | 강제 차단 | 외부 명령으로 차단 (관리 목적) |
| **FORCED_HALF_OPEN (5)** | 강제 복구 | 외부 명령으로 복구 시도 (테스트 용도) |

### 관련 문서
- **완전한 가이드**: [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)
  - 구현 상세 설명
  - 수동 테스트 방법
  - 모니터링 설정
  - 트러블슈팅

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
HikariCP 설정을 조정해서 200 RPS를 처리함:
```yaml
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 100  # 기본 10 → 100
SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: 20        # 기본 10 → 20
SERVER_TOMCAT_THREADS_MAX: 400                   # 기본 200 → 400
```

### Rate Limit 단계별 설정
부하 테스트를 위해 Rate Limit을 단계적으로 설정함:
- **개발 환경**: 1,000/1,000/500 (분당, authorize/capture/refund)
- **부하 테스트**: 15,000/15,000/7,500 (200 RPS 목표 + 25% 여유분)
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
2. **목표 설정**: 현재 200 RPS, 최종 1,000 TPS 처리
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

## GitHub Webhook 자동 빌드

### 개요
GitHub에 Push 시 Jenkins가 자동으로 빌드를 시작하도록 설정함.

### 구성 요소
1. **ngrok 터널**: 로컬 Jenkins를 외부에서 접근 가능하도록 설정함
2. **GitHub Webhook**: Push 이벤트 발생 시 Jenkins로 알림을 전송함
3. **Jenkins 설정**: GitHub hook trigger를 활성화함