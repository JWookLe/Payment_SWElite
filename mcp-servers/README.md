# Payment System MCP Servers

AI-powered Model Context Protocol (MCP) servers for operating and debugging the Payment_SWElite system.

## 개요

이 디렉토리는 **6개의 MCP 서버**를 포함하고 있으며, Claude나 다른 AI 어시스턴트가 결제 시스템을 자연어로 관리하고 디버깅할 수 있게 해줍니다.

### MCP란?

**Model Context Protocol (MCP)**는 Anthropic에서 만든 표준 프로토콜로, AI 모델이 외부 도구와 데이터에 접근할 수 있게 합니다. 각 MCP 서버는 특정 기능(데이터베이스 쿼리, Redis 캐시 관리 등)을 제공하는 "플러그인"처럼 동작합니다.

## MCP 서버 목록

### 1. Circuit Breaker MCP (`circuit-breaker-mcp`)

**목적**: Circuit Breaker 상태 모니터링 및 진단

**주요 기능**:
- `get_circuit_breaker_status` - 현재 상태 확인 (CLOSED/OPEN/HALF_OPEN)
- `diagnose_circuit_breaker` - 문제 진단 및 권장사항 제공
- `check_kafka_health` - Kafka 정상 여부 확인
- `analyze_failure_pattern` - Prometheus 메트릭 기반 실패 패턴 분석

**사용 예시**:
```
AI: "서킷 브레이커 상태 확인해줘"
→ get_circuit_breaker_status 호출
→ "✅ CLOSED - 정상 작동 중, 실패율 0.5%"

AI: "Kafka 정상이야?"
→ check_kafka_health 호출
→ "✅ YES - Circuit Breaker CLOSED, 모든 시스템 정상"

AI: "왜 오후 2시에 서킷 브레이커가 열렸어?"
→ diagnose_circuit_breaker 호출
→ "🔴 Kafka 브로커 다운으로 인한 실패율 100% 감지"
```

---

### 2. Database Query MCP (`database-query-mcp`)

**목적**: MariaDB 자연어 쿼리 및 데이터 분석

**주요 기능**:
- `query_payments` - 결제 거래 조회 (자연어 필터)
- `query_ledgers` - 원장 엔트리 조회 및 집계
- `check_outbox_status` - 미발행 이벤트 탐지 (Kafka 장애 시)
- `find_duplicate_idempotency` - 중복 멱등성 키 찾기
- `payment_statistics` - 결제 통계 생성
- `reconciliation_check` - 복식부기 검증 (차변=대변)

**사용 예시**:
```
AI: "지난 1시간 동안 실패한 결제 보여줘"
→ query_payments(filter="failed payments last hour")
→ "📊 3개 결제 발견: #123, #456, #789"

AI: "TEST_123 머천트의 환불 총합 계산해줘"
→ query_ledgers(query_type="by_merchant", merchant_id="TEST_123")
→ "총 환불: 150,000 KRW (5건)"

AI: "복식부기 균형 확인"
→ reconciliation_check()
→ "✅ BALANCED - 차변 1,000,000 = 대변 1,000,000"

AI: "5분 이상 발행 안 된 outbox 이벤트 있어?"
→ check_outbox_status(max_age_minutes=5)
→ "⚠️ 3개 미발행 이벤트 발견 (Circuit Breaker OPEN 시점)"
```

---

### 3. Redis Cache MCP (`redis-cache-mcp`)

**목적**: Redis 캐시 및 Rate Limiter 관리

**주요 기능**:
- `check_rate_limit` - 머천트별 Rate Limit 상태 확인
- `idempotency_lookup` - 멱등성 키 캐시 조회
- `cache_stats` - Redis 통계 (메모리, Hit Rate 등)
- `clear_rate_limit` - Rate Limit 초기화 (운영 도구)
- `list_rate_limit_keys` - 활성 Rate Limit 키 목록
- `find_merchants_hitting_limits` - 한계치 도달 머천트 탐지
- `ttl_analysis` - 만료 예정 키 분석

**사용 예시**:
```
AI: "MERCHANT_X의 authorize Rate Limit 확인"
→ check_rate_limit(merchant_id="MERCHANT_X", action="authorize")
→ "✅ OK - 250/1000 사용 (25%), 리셋까지 45초"

AI: "멱등성 키 ABC123이 캐시에 있어?"
→ idempotency_lookup(merchant_id="TEST", idempotency_key="ABC123")
→ "✅ 발견 - Payment #456, TTL 300초"

AI: "Redis 메모리 사용량은?"
→ cache_stats()
→ "Used: 45.2 MB, Hit Rate: 92.3%, Keys: 1,234"

AI: "Rate Limit 80% 이상 사용 중인 머천트 찾아줘"
→ find_merchants_hitting_limits(threshold_percentage=80)
→ "⚠️ 3개 머천트: MERCHANT_A (95%), MERCHANT_B (88%)"
```

---

### 4. Kafka Operations MCP (`kafka-operations-mcp`) ⭐ NEW

**목적**: Kafka 토픽, Consumer Lag, DLQ 관리

**주요 기능**:
- `list_topics` - Kafka 토픽 목록 조회
- `get_topic_stats` - 토픽별 메시지 수 및 파티션 정보
- `check_consumer_lag` - Consumer Group의 Lag 조회
- `list_consumer_groups` - 활성 Consumer Group 목록
- `get_dlq_messages` - Dead Letter Queue 메시지 조회
- `check_kafka_health` - Kafka 클러스터 헬스 체크
- `get_topic_details` - 토픽 상세 정보 (파티션, 레플리카 등)

**사용 예시**:
```
AI: "payment.captured 토픽에 메시지 몇 개 있어?"
→ get_topic_stats(topic="payment.captured")
→ "📊 45개 메시지, 3개 파티션"

AI: "Consumer Lag 확인해줘"
→ check_consumer_lag(group_id="payment-consumer-group")
→ "✅ Lag 0개 - 모두 처리 완료"

AI: "DLQ에 뭐 있어?"
→ get_dlq_messages(limit=10)
→ "⚠️ 3개 메시지 (FK 제약조건 위반)"
```

---

### 5. Load Test MCP (`loadtest-mcp`) ⭐ NEW

**목적**: k6 부하 테스트 실행 및 결과 분석

**주요 기능**:
- `run_load_test` - k6 부하 테스트 실행 (200 RPS 시나리오)
- `get_latest_result` - 최근 테스트 결과 조회
- `analyze_performance` - 성능 분석 및 권장사항
- `list_scenarios` - 사용 가능한 테스트 시나리오
- `get_test_history` - 과거 테스트 히스토리

**사용 예시**:
```
AI: "부하 테스트 돌려줘"
→ run_load_test(enable_capture=true)
→ "🚀 테스트 시작 (약 8분 소요)"

AI: "테스트 결과 확인"
→ analyze_performance()
→ "🏆 EXCELLENT - p95: 85ms, 성공률 99.8%"

AI: "지난주랑 비교해줘"
→ get_test_history(limit=5)
→ "📈 개선: p95 120ms → 85ms (-29%)"
```

---

### 6. System Health MCP (`system-health-mcp`) ⭐ NEW

**목적**: 전체 시스템 통합 모니터링

**주요 기능**:
- `check_all_services` - 모든 서비스 헬스 체크 (Circuit Breaker, Kafka, Redis, DB)
- `get_system_overview` - 시스템 전체 개요 (주요 지표 요약)
- `diagnose_issues` - 문제 자동 감지 및 진단

**사용 예시**:
```
AI: "전체 시스템 상태 확인"
→ check_all_services()
→ "✅ Circuit Breaker: CLOSED
   ✅ Kafka: 3개 브로커
   ✅ Redis: 299개 키
   ✅ Database: 복식부기 균형"

AI: "시스템 개요 보여줘"
→ get_system_overview()
→ "📊 오늘 총 1,234건, 123,456,000원"

AI: "문제 있는 거 찾아줘"
→ diagnose_issues()
→ "⚠️ 2개 문제:
   • Consumer Lag 12개
   • DLQ 3개 메시지"
```

---

## 설치 및 실행

### 사전 요구사항
- Node.js 18+
- npm or yarn
- 실행 중인 Payment System (docker-compose up)

### 1. 의존성 설치

모든 MCP 서버 빌드 (한 번에):

```bash
cd mcp-servers
cd circuit-breaker-mcp && npm install && npm run build && cd ..
cd database-query-mcp && npm install && npm run build && cd ..
cd redis-cache-mcp && npm install && npm run build && cd ..
cd kafka-operations-mcp && npm install && npm run build && cd ..
cd loadtest-mcp && npm install && npm run build && cd ..
cd system-health-mcp && npm install && npm run build && cd ..
```

또는 개별적으로:

```bash
cd circuit-breaker-mcp && npm install && npm run build
cd database-query-mcp && npm install && npm run build
cd redis-cache-mcp && npm install && npm run build
cd kafka-operations-mcp && npm install && npm run build
cd loadtest-mcp && npm install && npm run build
cd system-health-mcp && npm install && npm run build
```

### 2. 환경 변수 설정

**모든 새로운 MCP 서버는 monitoring-service API를 사용**하므로 환경 변수가 간단합니다:

```env
# 모든 MCP 서버 공통
API_BASE_URL=http://localhost:8082
```

> **참고**: 기존 database-query-mcp와 redis-cache-mcp도 monitoring-service를 사용하도록 업데이트되었습니다.

### 3. Claude Desktop 연동

`claude_desktop_config.json`에 **6개 MCP 서버 모두** 추가:

```json
{
  "mcpServers": {
    "payment-circuit-breaker": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/circuit-breaker-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-database": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/database-query-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-redis": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/redis-cache-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-kafka": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/kafka-operations-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-loadtest": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/loadtest-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    },
    "payment-system-health": {
      "command": "node",
      "args": ["<절대경로>/mcp-servers/system-health-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8082"
      }
    }
  }
}
```

> **Windows 예시**: `<절대경로>` → `C:\\Users\\flwls\\Payment_SWElite\\Payment_SWElite`
>
> **macOS/Linux 예시**: `<절대경로>` → `/home/user/Payment_SWElite`

**Claude Desktop 재시작 후 MCP 서버들이 자동으로 로드됩니다.**

---

## 사용 가이드

### 기본 MCP 서버 (코어 기능)

1. **circuit-breaker-mcp**: Circuit Breaker 상태 및 Kafka 헬스
2. **database-query-mcp**: 결제 데이터 조회 및 복식부기
3. **redis-cache-mcp**: Rate Limit 및 캐시 관리

### 고급 MCP 서버 (운영 도구)

4. **kafka-operations-mcp**: Kafka 토픽, Consumer Lag, DLQ 관리
5. **loadtest-mcp**: k6 부하 테스트 자동화
6. **system-health-mcp**: 전체 시스템 통합 모니터링

### 추천 사용 워크플로

#### 1. 일일 체크 (아침 시작 시)
```
Claude: "전체 시스템 상태 확인"
→ system-health-mcp의 check_all_services 사용
→ 모든 서비스 헬스 한눈에 확인
```

#### 2. 성능 모니터링 (주간)
```
Claude: "부하 테스트 돌리고 결과 분석해줘"
→ loadtest-mcp로 테스트 실행
→ 성능 추이 비교 및 권장사항 확인
```

#### 3. 장애 대응 (문제 발생 시)
```
Claude: "문제 진단해줘"
→ system-health-mcp의 diagnose_issues로 자동 진단
→ Circuit Breaker OPEN → kafka-operations-mcp로 DLQ 확인
→ Consumer Lag → kafka-operations-mcp로 상세 조회
```

#### 4. 데이터 분석 (비즈니스)
```
Claude: "오늘 결제 통계 보여줘"
→ database-query-mcp의 payment_statistics
→ 매출, 거래량, 가맹점별 분석
```

---

## MCP 서버 아키텍처

```
┌─────────────────┐
│  Claude Desktop │
└────────┬────────┘
         │ MCP Protocol (stdio)
         │
    ┌────┴─────────────────────────────────────────────┐
    │                                                   │
┌───▼───────┐ ┌──────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ Circuit   │ │ Database │ │ Redis  │ │  Kafka   │ │ LoadTest │ │  System  │
│ Breaker   │ │  Query   │ │ Cache  │ │Operations│ │          │ │  Health  │
└───┬───────┘ └────┬─────┘ └───┬────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
    │              │            │           │            │            │
    │ HTTP         │ HTTP       │ HTTP      │ HTTP       │ HTTP       │ HTTP
    │              │            │           │            │            │
┌───▼──────────────▼────────────▼───────────▼────────────▼────────────▼─────┐
│                   monitoring-service (포트 8082)                           │
│  - Circuit Breaker API      - Database API     - Redis API                │
│  - Kafka API                - Load Test API                               │
└───┬──────────────┬────────────┬───────────┬────────────┬────────────┬─────┘
    │              │            │           │            │            │
┌───▼──────┐  ┌───▼────┐  ┌───▼────┐  ┌───▼────┐  ┌───▼────┐  ┌───▼────┐
│ Ingest   │  │MariaDB │  │ Redis  │  │ Kafka  │  │   k6   │  │Prometheus│
│ Service  │  │        │  │        │  │        │  │        │  │          │
└──────────┘  └────────┘  └────────┘  └────────┘  └────────┘  └──────────┘
```

---

## 향후 계획

### Phase 2 (예정)

삭제됨 - 이미 구현 완료!

### Phase 3 (검토 중)

- **Prometheus Metrics MCP**: 자연어로 Prometheus 쿼리 실행
  - "지난 1시간 HTTP 요청 수는?"
  - "CPU 사용률 추이 보여줘"

- **Jenkins Pipeline MCP**: Jenkins 빌드 및 배포 자동화
  - "최근 빌드 상태 확인"
  - "파이프라인 실행해줘"

---

**이제 Claude Desktop에서 6개 MCP 서버로 거의 모든 시스템 운영 작업을 자연어로 처리할 수 있습니다!**

---

## Phase 1 달성 완료 ✅

- ✅ Circuit Breaker MCP
- ✅ Database Query MCP
- ✅ Redis Cache MCP
- ✅ Kafka Operations MCP
- ✅ Load Test (k6) MCP
- ✅ System Health MCP

---

**Claude Desktop 재시작 후 사용 예시**:

```
사용자: "시스템 전체 상태 알려줘"
Claude: [system-health-mcp 사용]
       ✅ 모든 서비스 정상!

사용자: "DLQ에 메시지 있어?"
Claude: [kafka-operations-mcp 사용]
       ✅ DLQ 비어있음

사용자: "부하 테스트 결과는?"
Claude: [loadtest-mcp 사용]
       🏆 EXCELLENT - p95 85ms, 99.8% 성공

사용자: "오늘 총 매출은?"
Claude: [database-query-mcp 사용]
       📊 총 1,234건, 123,456,000원
```

이제 **6개 MCP 서버**가 모든 운영 시나리오를 커버합니다! 🎉

---

## 사용 시나리오 (업데이트)

### 시나리오 1: 결제 트러블슈팅

**문제**: "결제 번호 12345가 캡처 안 됐는데 왜 그래?"

**AI의 MCP 사용 흐름**:
1. **Database MCP**: `query_payments` → status 확인
2. **Database MCP**: `check_outbox_status` → 이벤트 발행 여부 확인
3. **Circuit Breaker MCP**: `diagnose_circuit_breaker` → Kafka 장애 확인
4. **결론**: "오후 2:30 Kafka 다운으로 Circuit Breaker OPEN, outbox 이벤트 미발행"

---

### 시나리오 2: 성능 저하 분석

**문제**: "API가 느린데 뭐가 문제야?"

**AI의 MCP 사용 흐름**:
1. **Circuit Breaker MCP**: `check_kafka_health` → Kafka 정상 확인
2. **Redis MCP**: `cache_stats` → Hit Rate 30% (평소 90%)
3. **Database MCP**: `payment_statistics` → 트래픽 급증 확인
4. **결론**: "Redis 캐시 만료로 DB 쿼리 증가, 캐시 워밍 필요"

---

### 시나리오 3: Rate Limit 모니터링

**문제**: "특정 머천트가 429 에러를 받는다고 하는데?"

**AI의 MCP 사용 흐름**:
1. **Redis MCP**: `check_rate_limit(merchant_id="MERCHANT_X")` → 980/1000 사용 중
2. **Redis MCP**: `find_merchants_hitting_limits` → 다른 5개 머천트도 임계치 근접
3. **권장**: "정상 트래픽 패턴, Rate Limit 증가 또는 머천트에게 재시도 로직 안내"

---

## 개발 및 테스트

### 개발 모드 실행

```bash
# TypeScript 직접 실행 (빌드 없이)
npm run dev
```

### 테스트 (개발 중)

각 서버를 stdio 모드로 실행하여 수동 테스트:

```bash
node dist/index.js
# stdin으로 MCP 프로토콜 메시지 전송
```

---

## 아키텍처

```
┌─────────────────┐
│  Claude AI      │
└────────┬────────┘
         │ MCP Protocol (stdio)
         │
    ┌────┴─────────────────────┬─────────────────────┐
    │                          │                     │
┌───▼──────────┐   ┌──────────▼──────┐   ┌─────────▼────────┐
│ Circuit      │   │ Database        │   │ Redis            │
│ Breaker MCP  │   │ Query MCP       │   │ Cache MCP        │
└───┬──────────┘   └──────────┬──────┘   └─────────┬────────┘
    │                         │                     │
    │ HTTP                    │ JDBC                │ Redis Protocol
    │                         │                     │
┌───▼──────────┐   ┌──────────▼──────┐   ┌─────────▼────────┐
│ Ingest       │   │ MariaDB         │   │ Redis            │
│ Service API  │   │ (paydb)         │   │                  │
└──────────────┘   └─────────────────┘   └──────────────────┘
```

---

## 보안 고려사항

### 읽기 전용 권장
- Database MCP: 읽기 전용 쿼리만 수행
- Circuit Breaker MCP: 상태 조회만 (상태 변경 없음)
- Redis MCP: `clear_rate_limit`는 신중하게 사용

### 민감 정보
- `.env` 파일은 `.gitignore`에 추가됨
- DB 비밀번호는 환경 변수로 관리
- 프로덕션 환경에서는 읽기 전용 DB 유저 사용 권장

### 접근 제어
- MCP 서버는 localhost에서만 실행 (외부 노출 안 됨)
- Claude Desktop만 stdio로 접근 가능
- 네트워크 노출 없음 (stdio 통신)

---

## 트러블슈팅

### MCP 서버가 Claude에서 안 보여요
1. `npm run build` 실행 확인
2. `claude_desktop_config.json` 경로 확인 (절대 경로 사용)
3. Claude Desktop 완전히 재시작 (Tray 아이콘 종료 → 재실행)
4. 로그 확인: `%APPDATA%\Claude\logs\mcp*.log`

### 연결 오류 (Database/Redis)
1. Docker Compose가 실행 중인지 확인: `docker compose ps`
2. 포트가 열려있는지 확인: `netstat -an | findstr 3306` (MariaDB), `6379` (Redis)
3. 환경 변수 확인: `claude_desktop_config.json`의 `env` 섹션

### TypeScript 컴파일 오류
```bash
# 의존성 재설치
rm -rf node_modules package-lock.json
npm install

# tsconfig.json 확인
npm run build
```

---

## 향후 계획

### Phase 1 (현재) ✅
- Circuit Breaker MCP
- Database Query MCP
- Redis Cache MCP

### Phase 2 (예정)
- **Kafka Operations MCP**: DLQ 분석, 메시지 재발행
- **Outbox Management MCP**: 미발행 이벤트 자동 복구
- **Prometheus Metrics MCP**: 자연어 메트릭 쿼리

### Phase 3 (검토 중)
- **Multi-Service Health MCP**: 전체 스택 헬스 체크
- **Jenkins Pipeline MCP**: 배포 자동화
- **Load Test MCP**: k6 자동 실행 및 분석

---

## 기여

이 MCP 서버들은 Payment_SWElite 프로젝트의 일부입니다.

**개선 아이디어**:
- 새로운 MCP 도구 추가
- 에러 처리 개선
- 테스트 케이스 작성
- 문서 업데이트

---

## 라이선스

Payment_SWElite 프로젝트와 동일

---

## 참고 자료

- [MCP 공식 문서](https://modelcontextprotocol.io/)
- [Anthropic MCP SDK](https://github.com/anthropics/anthropic-sdk-typescript)
- [Claude Desktop 설정 가이드](https://docs.anthropic.com/claude/docs)
- Payment_SWElite 메인 README: `../README.md`
