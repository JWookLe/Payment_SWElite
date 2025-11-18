# 6주차 작업

## 0. 주간 목표

- KT Cloud VM1·VM2 환경에서 **운영자가 바로 진단·배포할 수 있는 접속/네트워크 절차** 정리
- 프런트엔드(5173)·Gateway 노출 상태 및 Docker compose 분리를 점검하여 **VM별 역할을 명확히 유지**
- Prometheus가 **컨테이너 이름 + 내부 IP를 혼용**해도 충돌 없이 관리되도록 Git/구성 전략 수립

### 핵심 메모

| 항목               | 현황/결론                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------- |
| OS/패키지 관리     | Rocky Linux 9.6 →`dnf` 사용, `apt` 명령 없음                                           |
| 외부 네트워크 진단 | `ping 8.8.8.8` 100% loss → 보안그룹/프록시 확인 필요                                     |
| 프런트 5173 접근   | 컨테이너 미기동 + 보안그룹 미개방 →`docker compose up frontend` 후 5173 허용 필요        |
| Compose 파일       | VM1(state)·VM2(app) 전용 파일 사용,`.git/info/exclude`에 등록하여 pull 방해 제거         |
| Prometheus 설정    | 컨테이너 이름 + 172.25.x.x IP 혼용 허용, 클라우드 버전(`prometheus_cloud.yml`)은 Git 무시 |

---

## 1. Compose 분리 & Git 관리

### 1-1. VM1(state) / VM2(app) 분리

- VM1: `docker-compose.state.yml`에 DB/Kafka/Redis/Eureka/ingest/monitoring/prometheus/grafana 서비스를 묶어 실행.
- VM2: `docker-compose.app.yml`에 gateway/worker/frontend 등 애플리케이션 계층만 포함.
- 각 VM에서 `git status -sb` 시 전용 파일이 계속 잡히지 않도록 `.git/info/exclude`에 해당 파일명을 추가.

### 1-2. Prometheus 설정 전략

- Prometheus가 한 인스턴스에서 VM1 내부 컨테이너(gateway, ingest 등)와 VM2 IP(172.25.0.79:8080 등)를 동시에 모니터링해야 하므로, “컨테이너 이름 + IP 혼용”을 허용하기로 결정.
- VM1 전용 설정은 `monitoring/prometheus/prometheus_cloud.yml`로 보관하고, `docker-compose.state.yml`에서 `./monitoring/prometheus/prometheus_cloud.yml:/etc/prometheus/prometheus.yml`로 마운트.
- 해당 파일은 VM1 로컬에서만 필요하므로 `.git/info/exclude`에 등록하여 pull 시 충돌을 방지.
- 공용 `prometheus.yml`은 필요 시 기본값으로 복구하거나 삭제 상태를 유지하고, 클라우드 버전만 운영.

### 1-3. Git 상태 정리

- `git status`에 로컬 전용 파일이 계속 뜨는 문제 해결을 위해 `git status -sb` 확인 → `echo '<파일경로>' >> .git/info/exclude`.
- Prometheus 기본 파일이 삭제 상태로 남지 않도록 `git checkout -- monitoring/prometheus/prometheus.yml`로 복원하거나, 삭제를 확정할 경우 커밋.

---

## 2. CORS 및 Gateway 라우팅 문제 해결

### 2-1. 403 Forbidden 오류 해결

오늘 아침 Admin Dashboard 접속 시 403 Forbidden 오류가 발생했다. 원인을 분석해보니 Gateway의 CORS 설정이 프론트엔드 요청을 차단하고 있었다.

**문제 상황:**

- Frontend(VM2 5173포트)에서 Gateway(VM2 8080포트)로 API 요청 시 403 에러
- 브라우저 콘솔에 CORS 관련 오류 메시지 확인

**해결 과정:**

1. Gateway의 `application.yml`에서 CORS 설정 확인
2. `GATEWAY_ALLOWED_ORIGINS` 환경변수가 localhost만 허용하고 있었음
3. VM2의 docker-compose.app.yml에 실제 프론트엔드 주소 추가:
   ```yaml
   GATEWAY_ALLOWED_ORIGINS: "http://localhost:5173,http://172.25.0.79:5173"
   ```
4. Gateway 재시작 후 정상 동작 확인

### 2-2. Gateway 라우팅 설정 점검

Admin API 요청이 제대로 라우팅되지 않는 문제도 발견했다.

**수정 내용:**

- `/api/admin/**` 경로가 Monitoring Service로 정확히 라우팅되도록 설정
- RewritePath 필터 적용하여 `/api/admin/` prefix 유지

---

## 3. Docker 멀티스테이지 빌드 적용

모든 백엔드 서비스에 멀티스테이지 빌드를 적용하여 로컬 Gradle 빌드 없이 Docker 이미지 내에서 컴파일하도록 변경했다.

### 3-1. 대상 서비스

- consumer-worker
- settlement-worker
- refund-worker
- eureka-server
- monitoring-service

### 3-2. 주요 변경사항

각 서비스별로 다음 파일들을 수정/생성:

**build.gradle.kts:**

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

repositories {
    mavenCentral()
}
```

- repositories 블록 추가하여 Gradle이 의존성을 다운로드할 수 있도록 함

**Dockerfile (멀티스테이지):**

```dockerfile
# Build stage
FROM gradle:8-jdk21 AS builder
WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre
# ... 런타임 설정
COPY --from=builder --chown=spring:spring /build/build/libs/*.jar app.jar
```

- Builder 스테이지에서 소스 코드 컴파일
- Runtime 스테이지에서 JAR만 복사하여 이미지 크기 최소화

**.dockerignore:**

- 불필요한 빌드 아티팩트 제외하여 빌드 컨텍스트 최적화

**settings.gradle.kts:**

- pluginManagement 블록 추가로 플러그인 버전 명시

### 3-3. Monitoring Service 특별 설정

Monitoring Service는 테스트 스크립트 실행을 위해 추가 패키지가 필요:

- curl: API 호출용
- bash: 스크립트 실행
- docker-ce-cli: Docker 명령어 실행 (Kafka 중지/시작)
- nodejs: MCP 서버 실행
- k6: 부하 테스트 도구

스크립트 및 테스트 파일 복사:

```dockerfile
COPY --chown=spring:spring scripts/ ./scripts/
COPY --chown=spring:spring loadtest/ ./loadtest/
RUN chmod +x ./scripts/*.sh || true
```

---

## 4. 데이터베이스 스키마 설정

Admin Dashboard의 Database Stats 테스트가 실패하여 원인을 파악했다.

### 4-1. 문제 상황

```
bad SQL grammar [SELECT COUNT(*) as count FROM ledger_entry]
Table 'paydb.ledger_entry' doesn't exist
```

테이블이 생성되지 않아 쿼리가 실패하고 있었다.

### 4-2. 해결 과정

1. MariaDB 접속 정보 확인:

   - Host: mariadb (Docker 네트워크 내)
   - Database: paydb
   - User: root / Password: 4535
2. 스키마 파일 실행:

   ```bash
   docker exec -i pay-mariadb mysql -uroot -p4535 paydb < backend/ingest-service/src/main/resources/schema.sql
   ```
3. 생성된 테이블:

   - payment (결제 정보)
   - ledger_entry (원장 기록)
   - outbox_event (이벤트 발행)
   - idem_response_cache (멱등성 캐시)
   - settlement_request (정산 요청)
   - refund_request (환불 요청)
4. payuser 권한 부여:

   ```sql
   GRANT ALL PRIVILEGES ON paydb.* TO 'payuser'@'%';
   FLUSH PRIVILEGES;
   ```

---

## 5. Circuit Breaker 테스트 구현

Admin Dashboard에서 Circuit Breaker 테스트를 실행할 수 있도록 기능을 구현했다.

### 5-1. 초기 문제

테스트 버튼을 누르면 즉시 실패로 표시되고, Grafana에서 request rate가 0으로 나타났다. 실제로 테스트가 실행되지 않고 있었다.

### 5-2. 원인 분석

1. **Gateway 컨테이너 참조 문제:**

   - 기존 스크립트가 `gateway_exec`를 사용하여 Gateway 컨테이너 내에서 curl 실행
   - VM1의 Monitoring Service 컨테이너에서는 Gateway가 없음 (VM2에 있음)
   - "No such container: gateway" 오류 발생
2. **Volume 마운트 확인:**

   - `/root/Payment_SWElite/scripts` → `/app/scripts` (read-only)
   - `/root/Payment_SWElite/loadtest` → `/app/loadtest`
   - Docker 이미지 내 스크립트가 아닌 호스트의 스크립트를 사용

### 5-3. 해결책

루트의 `scripts/test-circuit-breaker.sh` 파일 수정:

1. **API 호출 방식 변경:**

   ```bash
   # Before
   gateway_exec timeout "${timeout_seconds}" curl ...

   # After - 직접 curl 사용
   timeout "${timeout_seconds}" curl -sSf -X POST "${GATEWAY_BASE_URL}/payments/authorize" ...
   ```
2. **Health Check 방식 변경:**

   ```bash
   # Before
   ingest_curl -s -f "${API_BASE_URL}/actuator/health"

   # After - 직접 curl 사용
   curl -s -f "${API_BASE_URL}/actuator/health"
   ```
3. **환경변수 설정 (AdminTestService.java):**

   ```java
   processBuilder.environment().put("API_BASE_URL", "http://pay-ingest:8080");
   processBuilder.environment().put("GATEWAY_BASE_URL", gatewayBaseUrl + "/api");
   ```

   - API_BASE_URL: 같은 VM1 내의 ingest-service
   - GATEWAY_BASE_URL: VM2의 Gateway IP (172.25.0.79:8080)

### 5-4. Docker Socket 마운트

Kafka 중지/시작을 위해 Docker 명령어를 실행해야 하므로 docker-compose.state.yml에서 Docker socket 마운트:

```yaml
monitoring-service:
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
```

---

## 6. K6 부하 테스트 지원 추가

K6 부하 테스트도 Admin Dashboard에서 실행할 수 있도록 구현했다.

### 6-1. 파일 구성

- `scripts/run-k6-test.sh`: K6 테스트 실행 스크립트
- `loadtest/k6/payment-scenario.js`: 부하 테스트 시나리오
- 위 파일들이 Volume 마운트로 컨테이너에서 접근 가능

### 6-2. 테스트 시나리오

세 가지 시나리오 지원:

- authorize-only: 결제 승인만
- authorize-capture: 결제 승인 + 매입
- full-flow: 결제 승인 + 매입 + 환불

### 6-3. BASE_URL 설정

AdminTestService에서 K6 스크립트 실행 시 Gateway URL을 환경변수로 전달:

```java
processBuilder.environment().put("BASE_URL", gatewayBaseUrl + "/api");
```

### 6-4. 부하 테스트 설정

K6 시나리오는 ramping-arrival-rate 실행기를 사용:

- 30초: 100 RPS까지 웜업
- 1분: 200 RPS까지 증가
- 2분: 300 RPS까지 증가
- 2분: 400 RPS 도달
- 2분: 400 RPS 유지
- 30초: 쿨다운

임계값:

- HTTP 실패율 5% 미만
- 95 percentile 응답 시간 1초 미만
- 결제 오류율 2% 미만

현재 DB 자원 제한으로 인해 테스트 성공률이 100%는 아니지만, 테스트 자체는 정상 실행된다.

---

## 7. 최종 결과

### 7-1. 완성된 기능

1. **Admin Dashboard 모든 테스트 동작:**

   - Database Stats: 테이블별 레코드 수 조회
   - Redis Connection: Redis 연결 상태 확인
   - Kafka Health: Kafka 브로커 상태 확인
   - Settlement Stats: 정산 통계 조회
   - Circuit Breaker Test: Kafka 장애 시뮬레이션 및 복구 검증
   - K6 Load Test: 부하 테스트 실행
2. **멀티스테이지 빌드:**

   - 모든 백엔드 서비스가 Docker 내에서 컴파일
   - 로컬 Gradle 설치 없이도 빌드 가능
3. **CORS 및 라우팅:**

   - 프론트엔드-Gateway 간 CORS 정상 동작
   - 모든 API 경로 정확히 라우팅

### 7-2. VM 구성 현황

**VM1 (172.25.0.37):**

- MariaDB (13306)
- Kafka (9092), Zookeeper
- Redis
- Eureka Server (8761)
- Ingest Service (8081 → 결제 API)
- Monitoring Service (8082 → 관리자 API + 테스트 실행)
- Prometheus, Grafana (모니터링)

**VM2 (172.25.0.79):**

- API Gateway (8080)
- Frontend (5173)
- Consumer/Settlement/Refund Workers

### 7-3. 향후 개선사항

- K6 테스트 시 DB 자원 최적화로 성공률 향상 필요
- MCP 서버 연동으로 AI 기반 분석 보고서 생성
- 자동화된 CI/CD 파이프라인 구축

---

## 8. K6 부하 테스트 Rate Limit 문제 해결

K6 부하 테스트 실행 시 94%의 높은 실패율이 발생하여 원인을 분석하고 해결했다.

### 8-1. 문제 상황

```
✗ authorize status ok
  ↳  5% — ✓ 8000 / ✗ 128799
http_req_failed: 94.14% (128799 out of 136799)
```

대부분의 요청이 실패하고 있어 로그를 확인한 결과:

```
ERROR #1: status=429, error=, body={"code":"RATE_LIMIT_EXCEEDED"...}
```

HTTP 429 Too Many Requests 오류가 발생하고 있었다.

### 8-2. 원인 분석

Rate Limit 설정을 확인한 결과:

- 기본값: 60초당 1,000건 (authorize/capture)
- K6 테스트: 300-400 RPS = 분당 18,000-24,000건

K6가 초당 300-400건의 요청을 보내는데, Rate Limit은 분당 1,000건만 허용하고 있어 대부분의 요청이 차단되고 있었다.

### 8-3. 해결 방안

`backend/ingest-service/src/main/resources/application.yml` 수정:

**변경 전:**

```yaml
rate-limit:
  authorize:
    window-seconds: ${APP_RATE_LIMIT_AUTHORIZE_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_AUTHORIZE_CAPACITY:1000}
  capture:
    window-seconds: ${APP_RATE_LIMIT_CAPTURE_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_CAPTURE_CAPACITY:1000}
  refund:
    window-seconds: ${APP_RATE_LIMIT_REFUND_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_REFUND_CAPACITY:500}
```

**변경 후:**

```yaml
rate-limit:
  authorize:
    window-seconds: ${APP_RATE_LIMIT_AUTHORIZE_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_AUTHORIZE_CAPACITY:30000}  # 1,000 → 30,000
  capture:
    window-seconds: ${APP_RATE_LIMIT_CAPTURE_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_CAPTURE_CAPACITY:30000}    # 1,000 → 30,000
  refund:
    window-seconds: ${APP_RATE_LIMIT_REFUND_WINDOW_SECONDS:60}
    capacity: ${APP_RATE_LIMIT_REFUND_CAPACITY:15000}     # 500 → 15,000
```

분당 30,000건으로 설정하여 초당 500 RPS까지 여유있게 처리할 수 있도록 변경했다.

### 8-4. 결과

Rate Limit 조정 후 K6 테스트 결과:

```
✓ authorize status ok
  ↳  99% — ✓ 135993 / ✗ 16

http_req_failed: 0.01% (16 out of 136799)
http_req_duration: avg=106.74ms p(95)=152.23ms p(99)=236.91ms
iteration_duration: avg=1.1s
iterations: 136799 (284.57/s)
```

- **HTTP 실패율**: 94% → **0.01%** (대폭 개선)
- **처리량**: 284.57 RPS
- **평균 응답 시간**: 106.74ms
- **p95**: 152.23ms
- **p99**: 236.91ms

### 8-5. 남은 이슈

```
payment_errors: 100.00% (136799 out of 136799)
```

HTTP 요청 자체는 성공하지만 K6 스크립트에서 응답 본문의 paymentId를 파싱하지 못하는 문제가 있다. 이는 응답 형식 불일치로 인한 것으로, 부하 테스트 메트릭 자체에는 영향을 주지 않는다.

summary 확인 방법:

```bash
cat loadtest/k6/summary.json
```


---

## 9. 다음 작업: 800 RPS 목표 달성을 위한 스케일링 전략

현재 284.57 RPS를 달성했으며, 800 RPS 목표를 위해 다음 스케일링 방안을 검토한다.

### 9-1. 현재 병목 지점 분석

1. **단일 MariaDB 인스턴스**

   - Connection Pool 제한 (기본 HikariCP 10개)
   - 쓰기 작업 병목 (INSERT/UPDATE 경합)
   - 트랜잭션 락 대기 시간 증가
2. **단일 Kafka Broker**

   - 파티션당 단일 리더로 쓰기 병목
   - Producer ACK 대기 시간
   - Consumer Lag 누적 가능성
3. **단일 Redis 인스턴스**

   - Rate Limit 연산 집중
   - 캐시 히트율에 따른 성능 변동
   - 메모리 제한

### 9-2. 현실적인 스케일링 방안

#### 방안 1: Connection Pool 최적화 (가장 빠른 효과)

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50        # 10 → 50
      minimum-idle: 10             # 10 유지
      connection-timeout: 30000    # 30초
      idle-timeout: 600000         # 10분
      max-lifetime: 1800000        # 30분
```

- **장점**: 코드 변경 없이 설정만으로 개선
- **예상 효과**: 20-30% 처리량 향상
- **리스크**: DB 서버 부하 증가, 메모리 사용량 증가

#### 방안 2: MariaDB 읽기 복제본 (Read Replica) 추가

```yaml
# docker-compose.state.yml
mariadb-replica:
  image: mariadb:10.11
  environment:
    MYSQL_REPLICATION_MODE: slave
    MYSQL_REPLICATION_USER: repl_user
    MYSQL_REPLICATION_PASSWORD: repl_pass
    MYSQL_MASTER_HOST: mariadb
  volumes:
    - mariadb_replica_data:/var/lib/mysql
```

- **장점**: 읽기 쿼리 분산, 마스터 부하 감소
- **예상 효과**: 읽기 부하가 많은 경우 50% 이상 개선
- **리스크**: 복제 지연(Replication Lag), 데이터 일관성 이슈
- **적용 범위**: 조회 API (정산 통계, 결제 내역 조회 등)

#### 방안 3: Kafka Broker 3대 클러스터링

```yaml
# docker-compose.state.yml
kafka-1:
  image: confluentinc/cp-kafka:7.5.0
  environment:
    KAFKA_BROKER_ID: 1
    KAFKA_NUM_PARTITIONS: 6
  # ...

kafka-2:
  image: confluentinc/cp-kafka:7.5.0
  environment:
    KAFKA_BROKER_ID: 2
  # ...

kafka-3:
  image: confluentinc/cp-kafka:7.5.0
  environment:
    KAFKA_BROKER_ID: 3
  # ...
```

- **장점**: 고가용성, 파티션 분산으로 병렬 처리 향상
- **예상 효과**: Producer 처리량 2-3배 향상
- **리스크**: 리소스 사용량 증가, 복잡한 운영
- **필수 설정**: replication-factor=3, min.insync.replicas=2

#### 방안 4: Redis Sentinel (고가용성)

```yaml
redis-master:
  image: redis:7-alpine
  command: redis-server --requirepass redis123

redis-replica:
  image: redis:7-alpine
  command: redis-server --slaveof redis-master 6379 --requirepass redis123

redis-sentinel:
  image: redis:7-alpine
  command: redis-sentinel /etc/redis/sentinel.conf
```

- **장점**: 자동 장애 복구, 읽기 분산
- **예상 효과**: 가용성 향상, Rate Limit 연산 분산
- **리스크**: 설정 복잡도 증가

### 9-3. 현업에서의 우선순위 결정

#### Phase 1: 즉시 적용 (1일)

1. **HikariCP Connection Pool 증설** (50개로)
2. **JVM 힙 메모리 최적화** (Xms/Xmx 2G → 4G)
3. **K6 시나리오 최적화** (800 RPS 단계별 램프업)

예상 결과: 400-500 RPS 달성

#### Phase 2: 중기 개선 (2-3일)

1. **Kafka 파티션 증설** (3 → 12)
2. **Consumer Worker 인스턴스 증설** (1 → 3)
3. **DB 인덱스 최적화** (주요 조회 컬럼)

예상 결과: 600-700 RPS 달성

#### Phase 3: 확장 (3-5일)

1. **MariaDB Read Replica 구성**
2. **Kafka 3-Broker 클러스터**
3. **Redis Sentinel 구성**

예상 결과: 800+ RPS 달성

### 9-4. 모니터링 지표 설정

800 RPS 목표 달성을 위한 핵심 메트릭:

| 지표                 | 현재값   | 목표값 | 임계값   |
| -------------------- | -------- | ------ | -------- |
| RPS                  | 284.57   | 800    | 750 이상 |
| p95 응답시간         | 152.23ms | <300ms | 500ms    |
| p99 응답시간         | 236.91ms | <500ms | 1000ms   |
| HTTP 실패율          | 0.01%    | <0.1%  | 1%       |
| DB Connection 사용률 | 미측정   | <80%   | 90%      |
| Kafka Consumer Lag   | 미측정   | <1000  | 5000     |

### 9-5. 내일 실행 계획

1. **오전: Phase 1 적용**

   - HikariCP 설정 수정 (50 connections)
   - JVM 힙 메모리 증설
   - Ingest Service 재배포
2. **오후: K6 테스트 및 병목 분석**

   - 400 RPS 목표 테스트 실행
   - Grafana 대시보드로 리소스 사용률 확인
   - DB 커넥션 풀, CPU, 메모리 병목 지점 파악
3. **저녁: 결과 문서화**

   - 개선 전/후 메트릭 비교
   - 다음 Phase 우선순위 재조정
   - 필요 시 Phase 2 작업 시작

### 9-6. 리스크 및 대응 방안

| 리스크            | 영향             | 대응 방안                               |
| ----------------- | ---------------- | --------------------------------------- |
| DB 커넥션 고갈    | 서비스 중단      | 커넥션 풀 모니터링, 타임아웃 설정       |
| OOM (메모리 부족) | 컨테이너 재시작  | 힙 덤프 분석, GC 로그 확인              |
| Kafka Lag 급증    | 이벤트 처리 지연 | Consumer 인스턴스 증설, 파티션 리밸런싱 |
| 네트워크 병목     | 지연 시간 증가   | VM 간 대역폭 확인, 필요 시 같은 VM 배치 |

---

## 10. 기술 부채 정리

### 10-1. 해결된 이슈

- ✅ CORS 403 Forbidden 오류
- ✅ Gateway 라우팅 설정
- ✅ 멀티스테이지 Docker 빌드
- ✅ DB 스키마 생성
- ✅ Circuit Breaker 테스트 스크립트
- ✅ K6 부하 테스트 Rate Limit 문제

### 10-2. 남은 이슈

- ⏳ K6 응답 파싱 오류 (payment_errors 100%)
- ⏳ 800 RPS 목표 미달성 (현재 284.57 RPS)
- ⏳ MCP 서버 연동 미완료
- ⏳ CI/CD 파이프라인 부재

### 10-3. 코드 품질 개선 필요 사항

1. **응답 형식 표준화**: API 응답이 `{paymentId}` 또는 `{response: {paymentId}}`로 일관성 없음
2. **에러 핸들링 강화**: 구체적인 에러 코드와 메시지 제공
3. **로깅 표준화**: 구조화된 로그 포맷 (JSON)
4. **메트릭 수집 강화**: 비즈니스 KPI 대시보드 구축
