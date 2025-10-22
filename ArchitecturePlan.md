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

## Phase 5: 수평 확장 (2대 서버 추가)

### 목표: 1000 RPS 달성

### 상황: "추가로 같은 스펙 1대를 더 할당받음"

### 아키텍처 변경

```
Before (1대):
┌─────────────────────────────────────┐
│ Server 1 (4Core/16GB)               │
├─────────────────────────────────────┤
│ ingest-service (8080)               │
│ consumer-worker (8081)              │
│ Kafka Broker #1                     │
│ Redis                               │
│ MariaDB Primary                     │
└─────────────────────────────────────┘

After (2대):
┌──────────────────────┐  ┌──────────────────────┐
│ Server 1             │  │ Server 2             │
├──────────────────────┤  ├──────────────────────┤
│ ingest-service       │  │ ingest-service       │
│ consumer-worker      │  │ consumer-worker      │
│ Kafka Broker #1      │  │ Kafka Broker #2      │
│ Redis Master         │  │ Redis Replica        │
│ MariaDB Primary      │  │ MariaDB Replica      │
└──────────────────────┘  └──────────────────────┘
         ↓                        ↓
    ┌────────────────────────────┐
    │   Load Balancer (Nginx)    │ (Server 1 또는 별도)
    │   - Port 80/443            │
    │   - ingest-service 분산    │
    └────────────────────────────┘
```

### 1️⃣ 로드 밸런서 설정 (Nginx)

**파일**: `nginx.conf` (새로 생성)

```nginx
upstream api_servers {
  server server1:8080 weight=1;
  server server2:8080 weight=1;
}

server {
  listen 80;

  location / {
    proxy_pass http://api_servers;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
  }
}
```

**설정**: Server 1에서 Nginx 실행
```bash
# docker-compose.yml에 nginx 서비스 추가
nginx:
  image: nginx:latest
  volumes:
    - ./nginx.conf:/etc/nginx/nginx.conf
  ports:
    - "80:80"
  depends_on:
    - ingest-service
```

### 2️⃣ Kafka 클러스터화

**파일**: `docker-compose.yml` 수정

```yaml
# Server 1
kafka-1:
  KAFKA_BROKER_ID: 1
  KAFKA_ZOOKEEPER_CONNECT: zookeeper-1:2181,zookeeper-2:2181
  KAFKA_NUM_PARTITIONS: 2  # 파티션 2개 (2개 Consumer 대응)

# Server 2
kafka-2:
  KAFKA_BROKER_ID: 2
  KAFKA_ZOOKEEPER_CONNECT: zookeeper-1:2181,zookeeper-2:2181

# 토픽 재설정 (RF=2)
payment.authorized:
  replication-factor: 2
```

### 3️⃣ Redis Replication

**파일**: `docker-compose.yml` 수정

```yaml
# Server 1
redis-master:
  command: redis-server --port 6379
  ports:
    - "6379:6379"

# Server 2
redis-slave:
  command: redis-server --slaveof redis-master 6379
  links:
    - redis-master:redis-master
```

**application.yml 수정** (자동 failover 지원):
```yaml
spring:
  data:
    redis:
      url: redis://redis-master:6379
      timeout: 3000
```

### 4️⃣ MariaDB Replication

**Server 1 (Primary)**:
```sql
-- Primary 설정
CHANGE MASTER TO
  MASTER_USER='repl',
  MASTER_PASSWORD='password',
  MASTER_LOG_FILE='mysql-bin.000001',
  MASTER_LOG_POS=154;

START SLAVE;
```

**Server 2 (Replica)**:
```sql
-- Replica 설정
CHANGE MASTER TO
  MASTER_HOST='server1',
  MASTER_USER='repl',
  MASTER_PASSWORD='password';

START SLAVE;
```

**application.yml 수정**:
```yaml
# ingest-service (Server 1) - Write
spring:
  datasource:
    primary:
      url: jdbc:mariadb://server1:3306/paydb

# consumer-worker (모두) - Read Replica
spring:
  datasource:
    replica:
      url: jdbc:mariadb://server2:3306/paydb
```

### 5️⃣ Rate Limit 조정 (1000 RPS)

**파일**: `docker-compose.yml`

```yaml
# 2개 서버 × 500 RPS = 1000 RPS
ingest-service:
  environment:
    APP_RATE_LIMIT_AUTHORIZE_CAPACITY: 30000   # 각 서버당
    APP_RATE_LIMIT_CAPTURE_CAPACITY: 30000
    APP_RATE_LIMIT_REFUND_CAPACITY: 15000
```

### 6️⃣ 배포 및 검증

```bash
# Server 2에서 실행
ssh -i key.pem user@SERVER2_IP
bash deploy-single-server.sh

# 로드 밸런서 (Server 1) 재구동
docker compose up -d nginx

# 원격 테스트 (Load Balancer 경유)
curl -X POST http://LOADBALANCER_IP/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"TEST","amount":10000,"currency":"KRW","idempotencyKey":"test-1"}'

# k6로 1000 RPS 테스트
MSYS_NO_PATHCONV=1 docker run --rm \
  -e BASE_URL=http://LOADBALANCER_IP \
  -e MERCHANT_ID=K6TEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js
```

**체크리스트**:
```
□ Server 2 VM 생성 및 배포
□ Nginx 로드 밸런서 설정
□ Kafka 클러스터화 (2 Brokers)
□ Redis Replication 설정
□ MariaDB Replication 설정
□ Rate Limit 조정 (1000 RPS 분산)
□ 원격 테스트 실행 (1000 RPS)
□ Grafana 모니터링 확인
□ Git 커밋: "Scale: Add 2nd server, implement clustering (1000 RPS)"
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

### Phase 5 (수평 확장)
```
□ Server 2 추가
□ Nginx 로드 밸런서
□ Kafka 클러스터
□ Redis Replication
□ MariaDB Replication
□ 1000 RPS 검증
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
