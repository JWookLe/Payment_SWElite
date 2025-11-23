# 모니터링 서비스

결제 시스템의 상태와 운영 현황을 모니터링하는 REST API입니다. Circuit Breaker 모니터링, 데이터베이스 조회, Redis 캐시 점검 기능을 제공합니다.

## 기본 URL

- 로컬: `http://localhost:8082`
- API Gateway 경유: `http://localhost:8080/api/monitoring`

## API 엔드포인트

### Circuit Breaker 모니터링

#### GET `/monitoring/circuit-breaker/status`
현재 Circuit Breaker 상태와 분석 결과를 조회합니다

**응답 예시:**
```json
{
  "state": "CLOSED",
  "failureRate": "0.00%",
  "slowCallRate": "0.00%",
  "numberOfFailedCalls": 0,
  "numberOfSlowCalls": 0,
  "numberOfNotPermittedCalls": 0,
  "analysis": {
    "status": "Normal",
    "description": "Circuit breaker is closed. Kafka is operational."
  },
  "healthStatus": "HEALTHY"
}
```

#### GET `/monitoring/circuit-breaker/health`
Kafka가 정상적으로 동작하는지 간단하게 확인합니다.

**응답 예시:**
```json
{
  "healthy": true,
  "state": "CLOSED",
  "failureRate": "0.00%",
  "message": "Kafka is healthy"
}
```

#### GET `/monitoring/circuit-breaker/diagnose`
상세한 진단 결과와 권장사항을 제공합니다.

**응답 예시:**
```json
{
  "state": "CLOSED",
  "failureRate": "0.00%",
  "slowCallRate": "0.00%",
  "issues": ["No issues detected - Circuit Breaker is healthy"],
  "recommendations": ["No action required - system is operating normally"],
  "metrics": { ... }
}
```

---

### 데이터베이스 모니터링

#### GET `/monitoring/database/payments`
자연어 필터를 사용해서 결제 내역을 조회합니다.

**파라미터:**
- `filter` (필수): 자연어 필터 (예: "failed last hour", "merchant:merchant123", "over 10000")
- `limit` (선택, 기본값: 10): 최대 결과 개수

**사용 예시:**
```
GET /monitoring/database/payments?filter=failed last hour&limit=20
GET /monitoring/database/payments?filter=merchant:merchant123 today
GET /monitoring/database/payments?filter=completed over 50000
```

**응답 예시:**
```json
{
  "count": 5,
  "filter": "failed last hour",
  "payments": [
    {
      "payment_id": 123,
      "merchant_id": "merchant123",
      "amount": 10000,
      "currency": "KRW",
      "status": "CANCELLED",
      "idempotency_key": "key123",
      "requested_at": "2025-01-15T10:30:00",
      "updated_at": "2025-01-15T10:30:05"
    }
  ]
}
```

#### GET `/monitoring/database/statistics`
결제 통계를 조회합니다.

**파라미터:**
- `timeRange` (선택, 기본값: "today"): today, last_hour, last_24h, all

**응답 예시:**
```json
{
  "timeRange": "today",
  "overall": {
    "total_count": 1234,
    "total_amount": 123456000,
    "avg_amount": 100000,
    "min_amount": 1000,
    "max_amount": 500000
  },
  "byStatus": [
    { "status": "COMPLETED", "count": 1000, "total_amount": 100000000 },
    { "status": "CANCELLED", "count": 234, "total_amount": 23456000 }
  ],
  "topMerchants": [
    { "merchant_id": "merchant123", "transaction_count": 500, "total_amount": 50000000 }
  ]
}
```

#### GET `/monitoring/database/outbox`
발행되지 않고 대기 중인 outbox 이벤트를 확인합니다.

**파라미터:**
- `maxAgeMinutes` (선택, 기본값: 5): 이벤트가 정체된 것으로 판단하는 최소 경과 시간(분)

**응답 예시:**
```json
{
  "healthy": false,
  "message": "10 unpublished events older than 5 minutes",
  "count": 10,
  "events": [
    {
      "event_id": 1,
      "aggregate_type": "Payment",
      "aggregate_id": "123",
      "event_type": "PaymentAuthorized",
      "published": false,
      "created_at": "2025-01-15T10:00:00",
      "age_minutes": 30
    }
  ]
}
```

#### GET `/monitoring/database/reconciliation`
복식부기 무결성을 검증합니다.

**응답 예시:**
```json
{
  "balanced": true,
  "totalDebits": 1000000,
  "totalCredits": 1000000,
  "debitAccounts": [
    { "debit_account": "MERCHANT_SETTLEMENT", "total": 500000 }
  ],
  "creditAccounts": [
    { "credit_account": "CUSTOMER_REFUND", "total": 500000 }
  ],
  "message": "Books are balanced"
}
```

#### GET `/monitoring/database/ledger`
특정 결제나 가맹점의 원장 엔트리를 조회합니다.

**파라미터 (둘 중 하나 필수):**
- `paymentId`: 특정 결제의 원장 엔트리 조회
- `merchantId`: 특정 가맹점의 원장 엔트리 조회 (최근 20건)

**사용 예시:**
```
GET /monitoring/database/ledger?paymentId=123
GET /monitoring/database/ledger?merchantId=merchant123
```

**응답 예시:**
```json
{
  "count": 2,
  "entries": [
    {
      "entry_id": 1,
      "payment_id": 123,
      "debit_account": "MERCHANT_SETTLEMENT",
      "credit_account": "CUSTOMER_REFUND",
      "amount": 10000,
      "occurred_at": "2025-01-15T10:30:00"
    }
  ]
}
```

---

### Redis 모니터링

#### GET `/monitoring/redis/rate-limit`
가맹점의 Rate Limit 상태를 확인합니다.

**파라미터:**
- `merchantId` (필수): 확인할 가맹점 ID

**응답 예시:**
```json
{
  "merchantId": "merchant123",
  "currentCount": 50,
  "limit": 100,
  "remaining": 50,
  "ttlSeconds": 45,
  "status": "OK",
  "message": "Rate limit OK"
}
```

#### GET `/monitoring/redis/idempotency`
멱등성 키가 캐시에 존재하는지 확인합니다 (중복 감지).

**파라미터:**
- `key` (필수): 확인할 멱등성 키

**응답 예시:**
```json
{
  "exists": true,
  "idempotencyKey": "abc123",
  "paymentId": "pay_456",
  "ttlSeconds": 3540,
  "message": "Duplicate detected - payment pay_456 already processed with this key"
}
```

#### GET `/monitoring/redis/cache-stats`
Redis 캐시 통계를 조회합니다.

**응답 예시:**
```json
{
  "rateLimitKeys": 15,
  "idempotencyKeys": 234,
  "cacheKeys": 50,
  "totalKeys": 299,
  "rateLimitSamples": [
    { "key": "rate_limit:merchant123", "count": "50", "ttlSeconds": 45 }
  ],
  "message": "Redis cache statistics retrieved successfully"
}
```

#### DELETE `/monitoring/redis/rate-limit`
가맹점의 Rate Limit을 초기화합니다 (관리자 전용).

**파라미터:**
- `merchantId` (필수): 초기화할 가맹점 ID

**응답 예시:**
```json
{
  "success": true,
  "merchantId": "merchant123",
  "message": "Rate limit cleared successfully"
}
```

#### GET `/monitoring/redis/rate-limit/all`
Rate Limit이 활성화된 모든 가맹점 목록을 조회합니다.

**응답 예시:**
```json
{
  "count": 5,
  "rateLimits": [
    {
      "merchantId": "merchant123",
      "currentCount": 95,
      "ttlSeconds": 30,
      "status": "OK"
    },
    {
      "merchantId": "merchant456",
      "currentCount": 100,
      "ttlSeconds": 25,
      "status": "RATE_LIMITED"
    }
  ]
}
```

#### GET `/monitoring/redis/rate-limit/blocked`
현재 Rate Limit에 걸려있는 가맹점을 찾습니다.

**응답 예시:**
```json
{
  "count": 2,
  "blockedMerchants": [
    {
      "merchantId": "merchant456",
      "count": 100,
      "ttl": 25,
      "isBlocked": true
    }
  ],
  "message": "2 merchant(s) are rate limited"
}
```

#### GET `/monitoring/redis/ttl-analysis`
Redis 키들의 TTL 분포를 분석합니다.

**응답 예시:**
```json
{
  "totalKeys": 299,
  "analysis": {
    "rate_limit": {
      "count": 15,
      "avgTTLSeconds": 50,
      "expiringSoon": 3,
      "neverExpire": 0
    },
    "idempotency": {
      "count": 234,
      "avgTTLSeconds": 3500,
      "expiringSoon": 5,
      "neverExpire": 0
    }
  },
  "message": "TTL analysis completed"
}
```

---

## 배포 방법

### Docker Compose
```bash
docker compose up -d monitoring-service
```

### API Gateway를 통한 접근
모든 엔드포인트는 API Gateway를 통해서도 접근할 수 있습니다:
```bash
curl http://localhost:8080/api/monitoring/circuit-breaker/status
curl http://localhost:8080/api/monitoring/database/payments?filter=failed&limit=5
curl http://localhost:8080/api/monitoring/redis/rate-limit?merchantId=merchant123
```

### 직접 접근
```bash
curl http://localhost:8082/monitoring/circuit-breaker/status
curl http://localhost:8082/monitoring/database/payments?filter=failed&limit=5
curl http://localhost:8082/monitoring/redis/rate-limit?merchantId=merchant123
```

---

## MCP와의 연동

이 모니터링 서비스는 MCP 서버들과 동일한 기능을 제공합니다:
- `circuit-breaker-mcp` → `/monitoring/circuit-breaker/**`
- `database-query-mcp` → `/monitoring/database/**`
- `redis-cache-mcp` → `/monitoring/redis/**`

**MCP 서버 사용 용도:** Claude Desktop을 통한 로컬 AI 디버깅
**REST API 사용 용도:** 클라우드 배포, 팀 공유, Grafana 연동, CI/CD 모니터링

---

## 헬스 체크

```bash
curl http://localhost:8082/actuator/health
```

## 메트릭 (Prometheus)

```bash
curl http://localhost:8082/actuator/prometheus
```
