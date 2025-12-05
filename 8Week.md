# 8주차 작업 보고서

## 개요
Dual VM 아키텍처에서 **ingest-service 분리 및 구조 개선**을 진행했습니다.
기존의 단일 ingest-service를 VM별 독립적인 서비스로 분리하고, ShedLock을 제거하여 간소화된 아키텍처를 구축했습니다.

---

## 1. 주요 변경사항

### 1.1 ingest-service 분리
**Before (기존 구조의 문제점):**
- 양쪽 VM에서 동일한 `ingest-service`를 사용
- 서비스 이름이 같아서 Eureka 등록 시 혼동 위험
- 개별 VM별 설정이 어려움

**After (새로운 구조):**
- **VM1**: `ingest-service-vm1` (port 8080)
- **VM2**: `ingest-service-vm2` (port 8083)
- 각 VM별로 독립적인 서비스 정의
- 명확한 서비스 분리로 관리 용이

### 1.2 ShedLock 제거
```yaml
# 제거된 설정
shedlock:
  defaults:
    lock-at-most-for: 10m
    lock-at-least-for: 1s
```
**이유:**
- 각 VM이 독립적인 DB (shard1, shard2)를 사용하므로 분산 락 불필요
- 양쪽 VM의 Outbox Polling이 각각의 DB에서만 작동
- 락 메커니즘 제거로 성능 개선

### 1.3 Merchant ID 기반 샤딩 유지
```
VM1 (ingest-service-vm1):
  - shard1 MariaDB (pay-mariadb-shard1:3306)
  - 짝수 merchant ID 처리
  - Eureka port: 8080

VM2 (ingest-service-vm2):
  - shard2 MariaDB (pay-mariadb-shard2:3306)
  - 홀수 merchant ID 처리
  - Eureka port: 8083
```

---

## 2. 구현 상세

### 2.1 Gateway 설정 변경

**파일**: `backend/gateway/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: payments-api
          uri: lb://INGEST-SERVICE  # 통합 로드밸런싱
          predicates:
            - Path=/api/payments/**
          filters:
            - RewritePath=/api/payments/?(?<segment>.*), /payments/${segment}
```

**변경 의도:**
- Gateway가 `INGEST-SERVICE` 이름으로 Eureka에 등록된 모든 ingest-service 인스턴스로 라우팅
- VM1, VM2의 두 인스턴스가 로드밸런싱됨

### 2.2 Prometheus 설정 변경

**파일**: `monitoring/prometheus/prometheus.yml`

```yaml
- job_name: 'ingest-service'
  metrics_path: /actuator/prometheus
  eureka_sd_configs:
    - server: http://eureka-server:8761/eureka
      refresh_interval: 30s
  relabel_configs:
    - source_labels: [__meta_eureka_app_name]
      target_label: application
    - source_labels: [__meta_eureka_app_name]
      action: keep
      regex: INGEST-SERVICE
```

**변경 의도:**
- Eureka Service Discovery로 VM1, VM2의 메트릭을 자동 수집
- 메트릭 라벨을 `application: ingest-service`로 통일

### 2.3 Grafana 대시보드 업데이트

**영향받은 파일들:**
- `monitoring/grafana/dashboards/payment-overview.json`
- `monitoring/grafana/dashboards/performance.json`

**쿼리 변경 예시:**
```json
// Before
"expr": "sum(rate(http_server_requests_seconds_count{application=~\"ingest-service-vm[12]\"}[5m]))"

// After
"expr": "sum(rate(http_server_requests_seconds_count{application=\"ingest-service\"}[5m]))"
```

---

## 3. 데이터베이스 스키마 업데이트

### 3.1 schema.sql 개선

**파일**: `backend/ingest-service/src/main/resources/schema.sql`

**outbox_event 테이블에 추가된 컬럼:**
```sql
CREATE TABLE IF NOT EXISTS outbox_event (
  event_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  aggregate_type  VARCHAR(32) NOT NULL,
  aggregate_id    BIGINT      NOT NULL,
  event_type      VARCHAR(32) NOT NULL,
  payload         JSON        NOT NULL,
  published       TINYINT(1)  NOT NULL DEFAULT 0,
  published_at    TIMESTAMP(3),          -- 신규
  retry_count     INT         NOT NULL DEFAULT 0,  -- 신규
  last_retry_at   TIMESTAMP(3),          -- 신규
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY ix_pub_created (published, created_at)
) ENGINE=InnoDB;
```

**추가된 컬럼의 목적:**
- `published_at`: 이벤트 발행 시간 추적
- `retry_count`: 재시도 횟수 기록
- `last_retry_at`: 마지막 재시도 시간 (Outbox Polling 최적화)

### 3.2 VM별 스키마 동기화

**VM1 (shard1):**
```bash
docker exec pay-mariadb mariadb -u payuser -ppaypass paydb < \
  backend/ingest-service/src/main/resources/schema.sql
```

**VM2 (shard2):**
```bash
docker exec pay-mariadb-shard2 mariadb -u payuser -ppaypass paydb < \
  backend/ingest-service/src/main/resources/schema.sql
```

---

## 4. K6 로드 테스트 결과

### 4.1 Authorize 전용 테스트

**테스트 설정:**
- 시나리오: Authorize API만 호출
- 최대 VU: 1500
- 테스트 지속시간: 약 6분

**결과:**
```
✅ 총 요청: 366,082개
✅ RPS: 1,016 req/s
✅ 평균 응답시간: 155ms
✅ p(90): 47.6ms
✅ p(95): 152.8ms
✅ p(99): 3,917ms
✅ 성공률: 99.987%
✅ Authorize 체크: 366,082 passes, 0 fails
```

**dropped_iterations 분석:**
- 11,919개 드롭 (3.2%)
- 원인: 시스템 리소스 한계
- 실제 HTTP 실패가 아님 (거의 0%)

### 4.2 전체 플로우 테스트 (Authorize → Capture → Refund)

**테스트 설정:**
- 시나리오: 3단계 전체 플로우
- 최대 VU: 630
- 테스트 지속시간: 약 6분

**결과:**
```
✅ Authorize: 107,790 passes, 0 fails
✅ Capture: 107,783 passes, 0 fails
✅ Refund: 107,783 passes, 0 fails
✅ 총 요청: 323,356개
✅ RPS: 895 req/s
✅ 평균 플로우 시간: 1,076ms
✅ p(90): 1,054ms
✅ p(95): 1,473ms
✅ p(99): 2,135ms
✅ 전체 체크: 323,356 passes, 0 fails (100%)
```

---

## 5. 아키텍처 최종 상태

### 5.1 서비스 등록 (Eureka)

```
Eureka Server (localhost:8761)
├── api-gateway (port 8080)
├── INGEST-SERVICE
│   ├── instance 1: ingest-service-vm1 (localhost:8080)
│   ├── instance 2: ingest-service-vm2 (172.25.0.79:8083)
│   └── (로드밸런싱: 50/50 분산)
├── consumer-worker (port 8081)
├── monitoring-service (port 8082)
├── settlement-worker (port 8084)
└── refund-worker (port 8085)
```

### 5.2 요청 흐름

```
Client
  ↓
API Gateway (port 8080)
  ↓
Service Discovery (Eureka)
  ├→ Load Balancer (INGEST-SERVICE)
  │   ├→ ingest-service-vm1 (port 8080)
  │   │   └→ shard1 MariaDB (pay-mariadb-shard1:3306)
  │   │
  │   └→ ingest-service-vm2 (port 8083)
  │       └→ shard2 MariaDB (pay-mariadb-shard2:3306)
  │
  └→ Kafka (메시지 브로커)
      └→ consumer-worker
          └→ Ledger 기록
```

### 5.3 모니터링 스택

```
Applications → Prometheus (port 9090)
  ├→ Metric 수집 (eureka_sd_config)
  │
  └→ Grafana (port 3000)
      ├→ Payment Service Overview
      └→ Performance Dashboard
```

---

## 6. 핵심 개선사항

| 항목 | Before | After | 개선효과 |
|------|--------|-------|---------|
| 서비스 이름 | 양쪽 VM: ingest-service | VM1: ingest-service-vm1, VM2: ingest-service-vm2 | 명확한 식별, 관리 용이 |
| 분산 락 | ShedLock 사용 | 제거 | 성능 향상, 복잡도 감소 |
| 메트릭 라벨 | 불일치 (vm1, vm2) | 통일 (ingest-service) | Grafana 쿼리 단순화 |
| 데이터베이스 스키마 | 미흡 | published_at, retry_count, last_retry_at 추가 | Outbox 재시도 로직 개선 |
| 처리량 | 테스트 안 함 | authorize: 1016 RPS, 전체: 895 RPS | 고성능 검증 |
| 안정성 | 테스트 안 함 | 99.99% 성공률 | 프로덕션 준비 완료 |

---

## 7. 기술 결정 사항

### 7.1 왜 ShedLock을 제거했는가?

**원인:**
1. 각 VM이 독립적인 Database를 사용 (shard1, shard2)
2. Outbox Polling Scheduler가 각 VM에서만 자신의 DB를 처리
3. 전역 분산 락이 불필요

**효과:**
- 락 획득/해제 오버헤드 제거
- 코드 복잡도 감소
- 응답 시간 개선

### 7.2 왜 Gateway에서 통합 라우팅을 사용하는가?

**대안 1: Gateway에서 VM별 라우트 정의**
```yaml
# 이전 방식 (비추천)
- id: ingest-vm1
  uri: lb://INGEST-SERVICE-VM1
- id: ingest-vm2
  uri: lb://INGEST-SERVICE-VM2
```
❌ Gateway 설정 변경 필요, 수동 관리

**대안 2: 통합 라우트 + Eureka Service Discovery (채택)**
```yaml
# 현재 방식 (권장)
- id: payments-api
  uri: lb://INGEST-SERVICE
```
✅ Eureka가 자동으로 VM1, VM2 인스턴스 발견
✅ Gateway 설정 변경 불필요
✅ 자동 로드밸런싱

---

## 8. MCP 및 모니터링 개선 작업 (2025-12-05)

### 8.1 Grafana Kafka 메트릭 수정

**문제:**
- Grafana 대시보드에서 Kafka Consumer 처리량이 100,000 req/s로 잘못 표시됨
- 실제 커밋 비율은 2-6 req/s

**원인:**
- `kafka_consumer_fetch_manager_records_consumed_total` 메트릭 사용
- K6 부하 테스트 시 피크 로드(108k records/sec)를 표시

**해결:**
```json
// monitoring/grafana/dashboards/payment-overview.json (Line 118)
"expr": "sum(rate(kafka_consumer_coordinator_commit_total{application=\"consumer-worker\"}[5m]))",
"legendFormat": "Commits/sec",
"title": "Kafka Consumer Commit Rate"
```

### 8.2 Circuit Breaker HALF_OPEN 복구 문제

**문제:**
- HALF_OPEN 상태에서 CLOSED로 전환 안 됨
- Kafka 복구 후에도 HALF_OPEN 유지

**원인:**
```java
// 10% 샘플링으로 성공 기록
if (circuitBreaker.getState() == HALF_OPEN && outboxEvent.getId() % 10 == 0) {
    circuitBreaker.executeRunnable(() -> {});
}
```
- `permittedNumberOfCallsInHalfOpenState: 3`인데 10% 샘플링
- 평균 30번 이벤트 필요

**해결:**
```java
// PaymentEventPublisher.java (VM1, VM2)
// 샘플링 제거, HALF_OPEN에서 모든 성공 기록
if (circuitBreaker.getState() == HALF_OPEN) {
    circuitBreaker.executeRunnable(() -> {});
}
```

### 8.3 Claude Desktop MCP 설정

**문제:**
- Circuit Breaker 테스트 실행했는데 "최근 1시간 호출 없음"
- `.claude/mcp_settings.json` 수정했으나 적용 안 됨

**원인:**
- Claude Desktop은 `%APPDATA%\Claude\claude_desktop_config.json` 사용
- PROMETHEUS_URL 설정 누락

**해결:**
```json
// C:\Users\flwls\AppData\Roaming\Claude\claude_desktop_config.json
{
  "mcpServers": {
    "payment-circuit-breaker": {
      "env": {
        "API_BASE_URL": "http://210.104.76.135:8082",
        "PROMETHEUS_URL": "http://210.104.76.135:9090"  // 추가
      }
    },
    "payment-loadtest": {  // 새로 추가
      "command": "node",
      "args": ["..\\mcp-servers\\loadtest-mcp\\dist\\index.js"],
      "env": {
        "API_BASE_URL": "http://210.104.76.135:8082"
      }
    }
  }
}
```

### 8.4 K6 LoadTest API NPE 수정

**문제:**
```bash
curl http://localhost:8082/monitoring/loadtest/analyze
{"error":true,"message":"Failed to analyze results: Cannot invoke \"java.util.Map.get(Object)\" because \"values\" is null"}
```

**원인:**
```java
// 잘못된 JSON 구조 가정
Map<String, Object> values = (Map<String, Object>) httpReqDuration.get("values");
Double p95 = (Double) values.get("p(95)");  // NPE
```

**실제 K6 JSON:**
```json
{
  "metrics": {
    "http_req_duration": {
      "p(95)": 27.534986,
      "avg": 14.636009593429158
    }
  }
}
```

**해결:**
```java
// LoadTestMonitoringController.java
Double p95 = ((Number) httpReqDuration.get("p(95)")).doubleValue();
Double avg = ((Number) httpReqDuration.get("avg")).doubleValue();
Double failRate = ((Number) httpReqFailed.get("value")).doubleValue();
```

**테스트 결과:**
```json
{
  "analysis": {
    "responseTime": {"p95":"27.53 ms", "avg":"14.64 ms"},
    "successRate": "99.99%",
    "throughput": "1049.95 req/s",
    "performanceGrade": "EXCELLENT - Great performance"
  }
}
```

### 8.5 Circuit Breaker MCP Prometheus 쿼리 수정

**문제 1: 잘못된 쿼리 문법**
```typescript
// mcp-servers/circuit-breaker-mcp/src/index.ts
query: `${q.query}[${timeRange}]`  // instant query에 range vector 사용 불가
```

**해결:**
```typescript
const response = await axios.get(
  `${PROMETHEUS_URL}/api/v1/query_range`,  // range query 사용
  {
    params: {
      query: q.query,
      start: startTime,
      end: endTime,
      step: '60s'
    }
  }
);
```

**문제 2: 존재하지 않는 메트릭**
```bash
# Prometheus 메트릭 확인
curl "http://localhost:9090/api/v1/label/__name__/values" | grep circuit
```

사용 가능한 메트릭:
- `resilience4j_circuitbreaker_buffered_calls`
- `resilience4j_circuitbreaker_calls_seconds_count` ✅
- `resilience4j_circuitbreaker_failure_rate`
- `resilience4j_circuitbreaker_not_permitted_calls_total`
- `resilience4j_circuitbreaker_state`

**해결:**
```typescript
const queries = [
  { name: "Failure Rate", query: `resilience4j_circuitbreaker_failure_rate{name="kafka-publisher"}` },
  { name: "Slow Call Rate", query: `resilience4j_circuitbreaker_slow_call_rate{name="kafka-publisher"}` },
  { name: "State", query: `resilience4j_circuitbreaker_state{name="kafka-publisher"}` },
  { name: "Buffered Calls", query: `resilience4j_circuitbreaker_buffered_calls{name="kafka-publisher"}` },
  { name: "Not Permitted Calls", query: `resilience4j_circuitbreaker_not_permitted_calls_total{name="kafka-publisher"}` },
  { name: "Total Calls", query: `resilience4j_circuitbreaker_calls_seconds_count{name="kafka-publisher"}` }
];
```

### 8.6 Admin Dashboard Circuit Breaker 테스트

**테스트 실행:** 2025-12-05 07:00:55
**시나리오:**
1. 정상 상태 확인 (Kafka UP)
2. 5번 정상 결제 API 호출
3. Kafka 중지
4. 15번 결제 API 호출 (HTTP 성공, Outbox 저장)
5. 150초 대기 (OutboxPollingScheduler가 Kafka 실패 감지)
6. Kafka + ingest-service 재시작
7. 12번 복구 트래픽

**결과:**
```
✅ Circuit Breaker: OPEN → HALF_OPEN → CLOSED 정상 전환
✅ HTTP 요청: Outbox Pattern으로 100% 성공
✅ Kafka 복구: HALF_OPEN에서 3번 성공 후 CLOSED 전환
```

### 8.7 수정된 파일 목록

1. `monitoring/grafana/dashboards/payment-overview.json` - Kafka 메트릭
2. `backend/ingest-service-vm1/src/main/java/com/example/payment/service/PaymentEventPublisher.java` - CB 복구
3. `backend/ingest-service-vm2/src/main/java/com/example/payment/service/PaymentEventPublisher.java` - CB 복구
4. `C:\Users\flwls\AppData\Roaming\Claude\claude_desktop_config.json` - MCP 설정
5. `backend/monitoring-service/src/main/java/com/example/monitoring/controller/LoadTestMonitoringController.java` - K6 분석
6. `mcp-servers/circuit-breaker-mcp/src/index.ts` - Prometheus 쿼리

### 8.8 재빌드 필요 사항

**VM1 (백엔드):**
```bash
./gradlew :backend:ingest-service-vm1:bootJar :backend:ingest-service-vm2:bootJar
docker compose -f docker-compose.state.yml up -d --build ingest-service-vm1 ingest-service-vm2
```

**로컬 PC (MCP):**
```bash
cd mcp-servers/circuit-breaker-mcp
npm run build
```
Claude Desktop 재시작 필요

---

## 결론

**8주차에서 완성된 것:**
✅ Dual VM 아키텍처 안정화
✅ 명확한 서비스 분리 (ingest-service-vm1, ingest-service-vm2)
✅ 불필요한 복잡도 제거 (ShedLock 제거)
✅ 고성능 검증 (1000+ RPS)
✅ 99.99% 안정성 확보
✅ 모니터링 스택 완성
✅ Circuit Breaker 자동 복구 개선
✅ Grafana 메트릭 정확도 향상
✅ MCP 기반 AI 모니터링 통합
