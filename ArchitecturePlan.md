# 결제 시스템 확장 아키텍처 플랜

## 현황
- **목표**: 200 RPS → 1000 RPS
- **할당 리소스**: 4Core / 16GB / 100GB / Public IP 1개 (현재 1대, 추후 2대 가능)
- **상태**: 200 RPS 달성 완료

---

## Phase 1: 단일 서버 최적화 (현재 상태 - 1대 서버)

### 목표: 200 RPS → 400 RPS (안정적 처리)

### 1️⃣ 리소스 제약 반영 (메모리/CPU)

**문제**: 현재 docker-compose.yml은 이상적인 구조 기반. 실제 4Core/16GB에서는 메모리 부족 가능.

**해결책**:
```yaml
# docker-compose.yml 수정 포인트

ingest-service:
  resources:
    limits:
      cpus: "2"        # 4Core 중 2Core 할당
      memory: "2.5g"   # 2.5GB 할당

consumer-worker:
  resources:
    limits:
      cpus: "1.5"      # 1.5Core 할당
      memory: "2g"     # 2GB 할당

mariadb:
  resources:
    limits:
      cpus: "0.5"      # 0.5Core 할당
      memory: "3g"     # 3GB 할당 (DB는 메모리 중심)

redis:
  resources:
    limits:
      cpus: "0.25"
      memory: "800m"

kafka & zookeeper:
  resources:
    limits:
      cpus: "0.25"
      memory: "1g"
```

### 2️⃣ JVM 튜닝 (GC 최적화)

**현재 문제**: GC Pause로 인한 응답시간 증가

**수정 파일**: `backend/ingest-service/Dockerfile`

```dockerfile
# 변경 전
ENV JAVA_OPTS="-Xms512m -Xmx1024m"

# 변경 후 (메모리 제약 반영)
ENV JAVA_OPTS="-Xms1g -Xmx1.5g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:ParallelGCThreads=2"
```

**consumer-worker도 동일 적용**:
```dockerfile
ENV JAVA_OPTS="-Xms800m -Xmx1.2g -XX:+UseG1GC"
```

### 3️⃣ 스레드 풀 조정 (CPU 제약 반영)

**수정 파일**: `backend/ingest-service/src/main/resources/application.yml`

```yaml
# 변경 전 (이상적인 구조)
server:
  tomcat:
    threads:
      max: 400  # 너무 많음

spring:
  datasource:
    hikari:
      maximum-pool-size: 100   # 너무 많음

# 변경 후 (4Core 최적화)
server:
  tomcat:
    threads:
      max: 150  # 줄임 (CPU 2개 기준)

spring:
  datasource:
    hikari:
      maximum-pool-size: 30    # 줄임
      minimum-idle: 5
```

### 4️⃣ Rate Limit 조정 (400 RPS 목표)

**수정 파일**: `docker-compose.yml`

```yaml
ingest-service:
  environment:
    # 현재: 15,000 (200 RPS용)
    # 변경: 30,000 (400 RPS용)
    APP_RATE_LIMIT_AUTHORIZE_CAPACITY: 30000
    APP_RATE_LIMIT_CAPTURE_CAPACITY: 30000
    APP_RATE_LIMIT_REFUND_CAPACITY: 15000
```

### 5️⃣ 테스트 및 검증

**로컬에서 실행** (4Core/16GB 상황 시뮬레이션):
```bash
# Step 1: 최적화된 이미지 빌드
docker compose up --build

# Step 2: k6로 400 RPS까지 부하 증가
MSYS_NO_PATHCONV=1 docker run --rm \
  --network payment_swelite_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js

# Step 3: Grafana 확인 (http://localhost:3000)
# - CPU 사용률: 70-80% 이상
# - 메모리: 14GB 이상 사용
# - p95 응답시간: 100-150ms
```

**체크리스트**:
```
□ Dockerfile 수정 (JVM 튜닝)
□ application.yml 수정 (스레드 풀, CP)
□ docker-compose.yml 수정 (리소스 limits, Rate Limit)
□ k6로 400 RPS 테스트
□ Grafana에서 메트릭 확인
□ Git 커밋: "Optimize: Resource constraints for 4Core/16GB (target 400 RPS)"
```

---

## Phase 2: 코드 개선 (병렬 진행)

### Settlement/Reconciliation 기능 추가

**현재 상태**: Payment → Ledger Entry만 처리
**추가할 것**: Settlement (정산) + Reconciliation (회계 검증)

#### 1️⃣ 새 클래스 추가

**파일**: `backend/ingest-service/src/main/java/com/example/payment/service/SettlementService.java`

```java
@Service
public class SettlementService {

  // payment.captured 이벤트 처리 후 정산 로직
  public void settle(Long paymentId, long amount) {
    // 가맹점 정산 기록
    // settlement 테이블에 저장
  }
}
```

**파일**: `backend/consumer-worker/src/main/java/com/example/payment/consumer/service/ReconciliationService.java`

```java
@Service
public class ReconciliationService {

  // 매시간 실행: Ledger Entry 기반 회계 검증
  @Scheduled(cron = "0 0 * * * *")  // 매시간
  public void reconcile() {
    // 차변 합계 = 대변 합계 검증
    // 불일치 시 리포트 생성
  }
}
```

#### 2️⃣ 데이터베이스 테이블 추가

**파일**: `backend/ingest-service/src/main/resources/schema.sql`에 추가

```sql
-- 정산 기록
CREATE TABLE IF NOT EXISTS settlement (
  settlement_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id BIGINT NOT NULL,
  merchant_id VARCHAR(32) NOT NULL,
  settled_amount BIGINT NOT NULL,
  settled_at TIMESTAMP(3),
  FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
);

-- 회계 검증 리포트
CREATE TABLE IF NOT EXISTS reconciliation_report (
  report_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_date DATE NOT NULL,
  total_debit BIGINT NOT NULL,
  total_credit BIGINT NOT NULL,
  is_balanced BOOLEAN,
  created_at TIMESTAMP(3)
);
```

#### 3️⃣ Kafka 토픽 추가

**파일**: `backend/ingest-service/src/main/java/com/example/payment/config/KafkaTopicConfig.java`에 추가

```java
@Bean
public NewTopic paymentSettledTopic() {
  return TopicBuilder.name("payment.settled").partitions(1).replicas(1).build();
}

@Bean
public NewTopic reconciliationCompletedTopic() {
  return TopicBuilder.name("reconciliation.completed").partitions(1).replicas(1).build();
}
```

**체크리스트**:
```
□ SettlementService 작성
□ ReconciliationService 작성
□ schema.sql 테이블 추가
□ KafkaTopicConfig 수정
□ 단위 테스트 작성
□ Git 커밋: "Feature: Add Settlement/Reconciliation services"
```

---

## Phase 3: 성능 최적화 (코드 레벨)

### 1️⃣ DB 인덱싱 개선

**현재**: 기본 인덱스만 있음
**개선**: 자주 조회하는 쿼리에 최적화된 인덱스 추가

**파일**: `backend/ingest-service/src/main/resources/schema.sql`에 추가

```sql
-- 멱등성 조회 최적화
ALTER TABLE payment ADD INDEX idx_merchant_idem (merchant_id, idempotency_key);

-- Ledger 조회 최적화
ALTER TABLE ledger_entry ADD INDEX idx_payment_account
  (payment_id, debit_account, credit_account);

-- Settlement 조회 최적화
ALTER TABLE settlement ADD INDEX idx_merchant_date (merchant_id, settled_at);
```

### 2️⃣ Kafka 배치 처리

**파일**: `backend/ingest-service/src/main/resources/application.yml`에 추가

```yaml
spring:
  kafka:
    producer:
      batch-size: 16384        # 16KB 배치
      linger-ms: 10            # 10ms 대기
      compression-type: lz4    # LZ4 압축
```

### 3️⃣ Connection Pool 최적화

**파일**: `backend/ingest-service/src/main/resources/application.yml` 수정

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      auto-commit: false        # 트랜잭션 명시적 관리
```

**체크리스트**:
```
□ 인덱싱 추가 및 테스트
□ Kafka 배치 설정
□ Connection Pool 최적화
□ k6로 성능 재검증 (400 RPS)
□ Git 커밋: "Performance: DB indexing, Kafka batching, CP optimization"
```

---

## Phase 4: KT Cloud 단일 서버 배포 (1대)

### 목표: KT Cloud에서 안정적으로 400 RPS 처리

### 1️⃣ 배포 자동화 스크립트

**파일**: `deploy-single-server.sh` (새로 생성)

```bash
#!/bin/bash

# KT Cloud VM에서 실행
# 전제: Docker, Docker Compose 설치됨

# 1. 저장소 클론
git clone <your-repo> payment-system
cd payment-system

# 2. 빌드
docker compose up --build -d

# 3. 헬스 체크
until curl -f http://localhost:8080/actuator/health; do
  echo "Waiting for service..."
  sleep 5
done

# 4. 로그 확인
docker compose logs -f ingest-service

echo "✅ Deployment successful!"
```

### 2️⃣ 모니터링 설정

**Grafana 대시보드에서 추적할 메트릭**:
```
□ CPU 사용률 (목표: 70-80%)
□ 메모리 사용률 (목표: 14GB 이상)
□ p95 응답시간 (목표: 100-150ms)
□ 에러율 (목표: < 1%)
□ Kafka 소비 Lag
```

### 3️⃣ KT Cloud 접속 및 배포

```bash
# SSH로 VM 접속
ssh -i your-key.pem user@PUBLIC_IP

# 배포 스크립트 실행
bash deploy-single-server.sh

# 외부에서 테스트
curl -X POST http://PUBLIC_IP:8080/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"TEST","amount":10000,"currency":"KRW","idempotencyKey":"test-1"}'

# Grafana 접속
http://PUBLIC_IP:3000 (admin/admin)
```

**체크리스트**:
```
□ KT Cloud VM 생성 (4Core/16GB)
□ 배포 스크립트 작성 및 테스트
□ 모니터링 설정
□ 프론트엔드 접속 확인 (http://PUBLIC_IP:5173)
□ API 정상 작동 확인
□ k6로 원격 부하 테스트
□ Git 커밋: "Deploy: Single server on KT Cloud (target 400 RPS)"
```

---

## Phase 5: 계층별 분리 (3대 서버)

### 목표: 1000 RPS 달성 (API / 데이터 처리 / 인프라 완전 분리)

### 상황: "추가로 같은 스펙 2대를 더 할당받음"

### 핵심 아이디어
**계층별 완전 분리 (각각 독립적 리소스)**:
- Server 1: API 계층 (ingest-service) - HTTP 요청 처리만
- Server 2: 데이터 계층 (consumer-worker) - Kafka 이벤트 처리만
- Server 3: 인프라 계층 (Kafka, Redis, MariaDB) - 데이터 저장소

### 아키텍처 변경

```
Before (1대):
┌────────────────────────────────────────┐
│ Server 1 (4Core/16GB)                  │
├────────────────────────────────────────┤
│ ingest-service (200 RPS)               │
│ consumer-worker (200 RPS)              │
│ Kafka (1 Broker)                       │
│ Redis                                  │
│ MariaDB Primary                        │
└────────────────────────────────────────┘

After (3대 - 완전 분리):
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│ Server 1             │  │ Server 2             │  │ Server 3             │
│ (API Layer)          │  │ (Data Layer)         │  │ (Infrastructure)     │
├──────────────────────┤  ├──────────────────────┤  ├──────────────────────┤
│ ingest-service       │  │ consumer-worker      │  │ Kafka (Broker #1)    │
│ (400 RPS)            │  │ (400 RPS)            │  │ Redis                │
│                      │  │                      │  │ MariaDB Primary      │
│ Role:                │  │ Role:                │  │                      │
│ - HTTP 요청 처리     │  │ - 이벤트 소비        │  │ Role:                │
│ - 비즈니스 로직      │  │ - 데이터 변환/저장   │  │ - 데이터 저장소      │
│ - Rate Limit         │  │ - Ledger 생성        │  │ - 캐시               │
│ - 멱등성 검증        │  │ - 회계 검증          │  │ - 메시지 브로커      │
│ - Nginx (포트 80)    │  │                      │  │                      │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
         ↓                        ↓                          ↓
         └────────────────────────┼──────────────────────────┘
                    모두 Server 3의 인프라 접근
```

### 1️⃣ Server 1 (API Layer) - ingest-service 전용

**docker-compose.yml**:
```yaml
services:
  ingest-service:
    image: ingest-service:latest
    environment:
      # 400 RPS 처리
      APP_RATE_LIMIT_AUTHORIZE_CAPACITY: 30000
      APP_RATE_LIMIT_CAPTURE_CAPACITY: 30000
      APP_RATE_LIMIT_REFUND_CAPACITY: 15000

      SERVER_TOMCAT_THREADS_MAX: 250
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 40

      # Server 3 인프라 접근
      KAFKA_BOOTSTRAP_SERVERS: server3:9092
      REDIS_HOST: server3
      PAYMENT_DB_HOST: server3

    ports:
      - "8080:8080"
    resources:
      limits:
        cpus: "3.5"       # API 처리에 집중
        memory: "3.5g"

  nginx:
    image: nginx:latest
    ports:
      - "80:80"
    # ingest-service:8080으로 라우팅
```

### 2️⃣ Server 2 (Data Layer) - consumer-worker 전용

**docker-compose.yml**:
```yaml
services:
  consumer-worker:
    image: consumer-worker:latest
    environment:
      # 400 RPS 처리
      SPRING_KAFKA_CONSUMER_GROUP_ID: payment-consumer

      # Server 3 인프라 접근
      KAFKA_BOOTSTRAP_SERVERS: server3:9092
      PAYMENT_DB_HOST: server3

    resources:
      limits:
        cpus: "3.5"       # 데이터 처리에 집중
        memory: "3.5g"
```

### 3️⃣ Server 3 (Infrastructure Layer) - Kafka + Redis + MariaDB

**docker-compose.yml**:
```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.6.1
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_NUM_PARTITIONS: 2
      KAFKA_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"
    resources:
      limits:
        cpus: "1"
        memory: "3g"

  redis:
    image: redis:7.4-alpine
    command: redis-server
    ports:
      - "6379:6379"
    resources:
      limits:
        cpus: "0.5"
        memory: "2g"

  mariadb:
    image: mariadb:11.4
    environment:
      MARIADB_DATABASE: paydb
      MARIADB_USER: payuser
      MARIADB_PASSWORD: paypass
    ports:
      - "3306:3306"
    volumes:
      - mariadb-data:/var/lib/mysql
    resources:
      limits:
        cpus: "2"
        memory: "11g"
```

### 4️⃣ Nginx (로드 밸런서)

**역할**: Public IP (포트 80)로 들어온 요청을 ingest-service (포트 8080)로 전달

**간단한 설정** (`nginx.conf`):
```nginx
server {
  listen 80;
  location /payments {
    proxy_pass http://server1:8080;
  }
}
```

**docker-compose.yml에 추가** (Server 1):
```yaml
nginx:
  image: nginx:latest
  ports:
    - "80:80"
```

### 4️⃣ 배포 순서

**Step 1: Server 3 배포 (인프라 먼저)**
```bash
ssh -i key.pem user@SERVER3_IP

git clone <repo>
docker compose up -d  # Kafka, Redis, MariaDB 시작

# 준비 확인
sleep 30
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

**Step 2: Server 1 배포 (API Layer)**
```bash
ssh -i key.pem user@SERVER1_IP

git clone <repo>
# docker-compose.yml에서 server3를 환경변수로 설정
docker compose up -d

# 확인
curl http://localhost:8080/actuator/health
```

**Step 3: Server 2 배포 (Data Layer)**
```bash
ssh -i key.pem user@SERVER2_IP

git clone <repo>
docker compose up -d

# 확인
docker logs -f consumer-worker
```

**Step 4: 통합 테스트**
```bash
# API 요청 (Nginx 경유)
curl -X POST http://<SERVER1_IP>/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"TEST","amount":10000,"currency":"KRW","idempotencyKey":"test-1"}'

# 1000 RPS 부하 테스트
MSYS_NO_PATHCONV=1 docker run --rm \
  -e BASE_URL=http://<SERVER1_IP> \
  -e MERCHANT_ID=K6TEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js
```

### 5️⃣ 리소스 배분 최적화

```
Server 1 (4Core/16GB) - API:
  - ingest-service: 3.5Core / 3.5GB
  - Nginx: 0.5Core / 0.5GB

Server 2 (4Core/16GB) - Data:
  - consumer-worker: 3.5Core / 3.5GB
  - 여유: 0.5Core / 0.5GB

Server 3 (4Core/16GB) - Infrastructure:
  - MariaDB: 2Core / 11GB (데이터 집약적)
  - Kafka: 1Core / 3GB
  - Redis: 0.5Core / 2GB
  - OS: 0.5Core / 0GB
```

### 6️⃣ 최종 WAS 구성 (Phase 5)

**총 3개 WAS**:
```
Server 1 (API Layer):
  └─ WAS #1: ingest-service (8080)
     └─ Nginx (80) - Public IP 진입점

Server 2 (Data Layer):
  └─ WAS #2: consumer-worker (8081)

Server 3 (Infrastructure):
  └─ WAS #3: 없음 (Kafka, Redis, MariaDB만)
```

**리소스 분리의 장점**:
- 각 계층이 독립적 리소스 (경쟁 없음)
- 확장 용이 (필요한 계층만 서버 추가)
- 병목 최소화

**총 처리 능력**: 400 RPS (API) + 400 RPS (Data) = **1000 RPS**

### 7️⃣ 체크리스트

```
□ Server 3 배포 (Kafka + Redis + MariaDB)
  - 포트 확인: 9092 (Kafka), 6379 (Redis), 3306 (MariaDB)
  - 디스크 여유 확인 (MariaDB용 최소 20GB)

□ Server 1 배포 (ingest-service + Nginx)
  - Server 3에 정상 연결 확인
  - 포트 확인: 8080 (ingest), 80 (Nginx)

□ Server 2 배포 (consumer-worker)
  - Server 3에 정상 연결 확인
  - 포트 확인: 8081 (consumer-worker)

□ 통합 테스트
  - E2E 흐름 검증 (authorize → capture → refund)
  - k6로 1000 RPS 부하 테스트
  - Kafka Lag 확인 (< 100ms)

□ 모니터링
  - 각 Server의 CPU/메모리 사용률 추적
  - Grafana 대시보드 구성

□ Git 커밋
  - "Scale: 3-layer separation (API/Data/Infrastructure) - 1000 RPS"
```

---

## 최종 체크리스트

### Phase 1 (리소스 최적화)
```
□ docker-compose.yml 리소스 제약 추가
□ Dockerfile JVM 튜닝
□ application.yml 스레드 풀/CP 조정
□ Rate Limit 30,000으로 변경
□ k6로 400 RPS 검증
```

### Phase 2 (기능 추가)
```
□ Settlement/Reconciliation 서비스 구현
□ schema.sql 테이블 추가
□ KafkaTopicConfig 수정
□ 단위 테스트 작성
```

### Phase 3 (성능 최적화)
```
□ DB 인덱싱 추가
□ Kafka 배치 처리
□ Connection Pool 최적화
```

### Phase 4 (단일 서버 배포)
```
□ KT Cloud VM 생성
□ 배포 스크립트 작성
□ 모니터링 설정
□ 원격 부하 테스트
```

### Phase 5 (3계층 분리 - 최종)
```
□ Server 3 (인프라) 배포: Kafka + Redis + MariaDB
□ Server 1 (API) 배포: ingest-service + Nginx
□ Server 2 (Data) 배포: consumer-worker
□ 서버 간 네트워크 연결 확인
□ E2E 통합 테스트
□ k6로 1000 RPS 검증
```

---

## 예상 타임라인

| 기간 | Phase | 목표 RPS | 작업량 |
|------|-------|---------|--------|
| 1주 | 1+2+3 | 400 | 3-4일 |
| 2주 | 4 | 400 (KT Cloud) | 2-3일 |
| 3주 | 5 | 1000 | 3-4일 |

---

## 주의사항

### 테스트 필수
- 각 Phase마다 k6로 부하 테스트 수행
- Grafana에서 메트릭 확인

### 모니터링 필수
- CPU, 메모리, 응답시간 추적
- 병목 지점 파악 및 튜닝

### Git 커밋 규칙
```
Optimize: <변경 사항>
Feature: <기능 추가>
Deploy: <배포>
Scale: <확장>
```

---

## 결론

**1대 서버 (4Core/16GB)**: 400 RPS 안정적 처리
**2대 서버**: 1000 RPS 달성

**핵심은 "단계별 최적화"**
- 무리하게 다 하려고 하지 말 것
- 각 Phase마다 검증 필수
- 병목 지점을 파악하고 최적화
