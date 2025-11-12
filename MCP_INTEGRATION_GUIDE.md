# MCP Claude API 통합 가이드

Admin Dashboard와 Claude API를 MCP를 통해 통합하여 **자동 AI 분석 보고서**를 생성하는 방법입니다.

---

## 🎯 개요

테스트 실행 → 결과 수집 → **Claude가 자동으로 분석** → 보고서 생성

```
User → Admin UI → Backend API → 테스트 실행
                              ↓
                         결과 수집
                              ↓
                    MCP Server (stdio)
                              ↓
                    Claude API 호출
                              ↓
                    AI 분석 결과 반환
                              ↓
                    Frontend에 자동 표시
```

---

## 📋 사전 준비

### 1. Anthropic API 키 발급

1. https://console.anthropic.com/ 접속
2. 로그인 / 회원가입
3. **Settings → API Keys** 메뉴
4. **Create Key** 클릭
5. 키 복사 (sk-ant-api03-...)

### 2. Node.js 설치 확인

```bash
node --version  # v18 이상 권장
npm --version
```

---

## 🚀 설치 및 설정

### 1단계: MCP 서버 빌드

```bash
cd mcp-servers/ai-test-analyzer

# 의존성 설치
npm install

# TypeScript 빌드
npm run build

# 빌드 확인
ls dist/  # index.js 파일이 있어야 함
```

### 2단계: API 키 설정

#### 방법 A: 환경 변수 (권장)

```bash
export ANTHROPIC_API_KEY=sk-ant-api03-your-key-here
```

Windows (PowerShell):
```powershell
$env:ANTHROPIC_API_KEY="sk-ant-api03-your-key-here"
```

#### 방법 B: .env 파일

프로젝트 루트에 `.env` 파일 생성:

```bash
# .env
ANTHROPIC_API_KEY=sk-ant-api03-your-key-here
MCP_AI_ANALYZER_ENABLED=true
MCP_AI_ANALYZER_PATH=mcp-servers/ai-test-analyzer
```

**주의**: `.env` 파일은 `.gitignore`에 포함되어 있습니다. 절대 커밋하지 마세요!

### 3단계: Docker Compose 환경 변수 설정

`docker-compose.yml`의 `monitoring-service` 섹션에 환경 변수 추가:

```yaml
monitoring-service:
  build:
    context: ./backend/monitoring-service
    dockerfile: Dockerfile
  # ... 기존 설정 ...
  environment:
    ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    MCP_AI_ANALYZER_ENABLED: "true"
    MCP_AI_ANALYZER_PATH: "mcp-servers/ai-test-analyzer"
  # ... 기존 설정 ...
```

### 4단계: Backend 재빌드 및 실행

```bash
# Backend 빌드
./gradlew :backend:monitoring-service:bootJar

# Docker Compose 재시작
docker compose down
docker compose up -d
```

---

## 🧪 테스트

### 1. MCP 서버 직접 테스트

```bash
cd mcp-servers/ai-test-analyzer

# 도구 목록 확인
echo '{"method":"tools/list"}' | ANTHROPIC_API_KEY=your_key node dist/index.js

# 출력 예시:
# {
#   "tools": [
#     { "name": "analyze_k6_test", ... },
#     { "name": "analyze_circuit_breaker_test", ... }
#   ]
# }
```

### 2. K6 분석 테스트

```bash
echo '{
  "method": "tools/call",
  "params": {
    "name": "analyze_k6_test",
    "arguments": {
      "testId": "test-1",
      "scenario": "authorize-only",
      "rawData": {
        "exitCode": 0,
        "k6Summary": {
          "metrics": {
            "http_req_duration": {
              "values": {
                "avg": 127.34,
                "p(95)": 245.67,
                "p(99)": 389.12
              }
            },
            "http_req_failed": {
              "values": {
                "rate": 0.005
              }
            },
            "http_reqs": {
              "values": {
                "count": 24000
              }
            }
          }
        }
      }
    }
  }
}' | ANTHROPIC_API_KEY=your_key node dist/index.js
```

### 3. Admin Dashboard에서 테스트

1. http://localhost:5173/admin 접속
2. "K6: 승인 전용" 카드에서 **"테스트 실행"** 클릭
3. 테스트 완료 후 **"보고서 보기 ▼"** 클릭
4. AI 분석 결과 확인:
   - **AI 분석 요약**: Claude가 작성한 종합 분석
   - **주요 메트릭**: 자동 추출된 성능 지표
   - **개선 권장사항**: AI가 제안하는 최적화 방안

---

## 📊 AI 분석 예시

### K6 부하 테스트 분석 결과

```
=== AI 자동 분석 보고서 ===

**Executive Summary**
K6 부하 테스트가 성공적으로 완료되었습니다. 시스템은 400 RPS의 부하 상황에서
99.5%의 높은 성공률을 유지하며 안정적으로 동작했습니다. P95 응답 시간은
245.67ms로 목표 임계값(1초) 이내에 있으나, 추가 최적화 여지가 있습니다.

**Performance Metrics Analysis**
- **Success Rate**: 99.5% (24,000건 중 23,880건 성공)
- **Average Response Time**: 127.34ms - 양호한 수준
- **P95 Response Time**: 245.67ms - 목표치 이내, 개선 가능
- **P99 Response Time**: 389.12ms - 일부 느린 요청 존재

**Bottlenecks & Issues**
1. P99 응답 시간이 P95 대비 1.5배 증가: 일부 요청이 현저히 느림
2. 0.5%의 실패율: 재시도 로직 또는 타임아웃 설정 점검 필요

**Recommendations**
• Database connection pool 크기를 현재 설정보다 20% 증가 (10 → 12)
• Redis cache hit rate 개선을 위해 TTL 전략 재검토
• P99 응답 시간 개선을 위해 느린 쿼리 최적화 (slow query log 분석)
• Circuit breaker 임계값 조정: slow call duration을 500ms → 400ms로 감소
• 실패한 요청의 에러 로그 분석 및 재시도 정책 검토
```

### Circuit Breaker 분석 결과

```
=== AI 자동 분석 보고서 ===

**Summary**
Circuit Breaker가 설계대로 정확하게 작동했습니다. Kafka 다운타임 시뮬레이션 중
CLOSED → OPEN 상태 전환이 즉시 이루어졌으며, 복구 후 HALF_OPEN → CLOSED로
정상 복귀했습니다.

**State Transitions**
1. 초기 상태 (CLOSED): 5개 요청 모두 성공
2. Kafka 중단 후: 6번째 slow call에서 OPEN으로 전환
3. Kafka 복구 후: HALF_OPEN 상태에서 1개 테스트 요청 성공
4. 최종 상태 (CLOSED): 정상 복구

**Recovery Behavior**
- Recovery Time: 약 15초 (Kafka 재시작 → 정상 요청까지)
- Half-Open Duration: 3초 이내 CLOSED로 전환
- 복구 과정에서 추가 실패 없음

**Recommendations**
• Circuit breaker의 slow call threshold (현재 5초)를 3초로 단축 권장
• Minimum number of calls (현재 10) 설정이 적절함
• Wait duration in open state를 15초 → 10초로 단축 고려
• 복구 시나리오 자동화 테스트를 CI/CD에 통합
```

---

## ⚙️ 설정 옵션

### application.yml (monitoring-service)

```yaml
mcp:
  ai-analyzer:
    enabled: true                              # MCP 활성화/비활성화
    path: mcp-servers/ai-test-analyzer        # MCP 서버 경로
```

### 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `ANTHROPIC_API_KEY` | Claude API 키 (필수) | - |
| `MCP_AI_ANALYZER_ENABLED` | MCP 활성화 여부 | `true` |
| `MCP_AI_ANALYZER_PATH` | MCP 서버 경로 | `mcp-servers/ai-test-analyzer` |

### Fallback 동작

MCP가 비활성화되거나 실패할 경우, 자동으로 기본 분석으로 대체됩니다:

```java
// MCP 실패 시 자동 Fallback
Map<String, Object> analysis = mcpAnalysisService.analyzeK6Test(...);
// → MCP 실패 → generateFallbackAnalysis() 호출
```

---

## 🐛 트러블슈팅

### 1. "ANTHROPIC_API_KEY not set" 에러

**증상**: MCP 서버 로그에 "ANTHROPIC_API_KEY: Not set" 표시

**해결**:
```bash
export ANTHROPIC_API_KEY=sk-ant-...
# 또는
echo "ANTHROPIC_API_KEY=sk-ant-..." >> .env
```

### 2. "MCP server directory not found" 에러

**증상**: Backend 로그에 "MCP server directory not found: mcp-servers/ai-test-analyzer"

**해결**:
```bash
# MCP 서버 빌드 확인
cd mcp-servers/ai-test-analyzer
npm run build
ls dist/  # index.js 파일 확인

# 경로 확인
pwd  # 프로젝트 루트 확인
```

### 3. MCP 응답이 없음 (Fallback 분석만 표시)

**증상**: AI 분석 요약에 "상세 분석을 위해서는 MCP 서버와 Claude API 연동이 필요합니다" 표시

**원인 및 해결**:

1. **Node.js 미설치**:
   ```bash
   node --version  # 설치 확인
   ```

2. **MCP 빌드 미완료**:
   ```bash
   cd mcp-servers/ai-test-analyzer
   npm run build
   ```

3. **API 키 누락**:
   ```bash
   echo $ANTHROPIC_API_KEY  # 키 확인
   ```

4. **로그 확인**:
   ```bash
   docker compose logs monitoring-service | grep MCP
   ```

### 4. Claude API Rate Limit 에러

**증상**: 429 Too Many Requests 에러

**해결**:
- Claude API 요금제 확인 (https://console.anthropic.com/settings/limits)
- 테스트 빈도 조절
- Tier 1 → Tier 2 이상으로 업그레이드

### 5. Permission Denied (Node.js 실행 권한)

**증상**: "EACCES: permission denied, open 'dist/index.js'"

**해결**:
```bash
chmod +x mcp-servers/ai-test-analyzer/dist/index.js
```

---

## 💰 비용 안내

### Claude API 요금 (Claude 3.5 Sonnet 기준)

- **Input**: $3.00 / MTok (million tokens)
- **Output**: $15.00 / MTok

### 예상 비용

| 테스트 타입 | Input Tokens | Output Tokens | 비용/회 |
|-------------|--------------|---------------|---------|
| K6 부하 테스트 | ~1,500 | ~800 | **$0.02** |
| Circuit Breaker | ~800 | ~500 | **$0.01** |
| Health Check | ~500 | ~300 | **$0.006** |
| 모니터링 통계 | ~600 | ~400 | **$0.008** |

**월 예상 비용** (테스트 100회 가정):
- K6 테스트 20회: $0.40
- 기타 테스트 80회: $0.70
- **총 약 $1.10/월**

---

## 🔐 보안 고려사항

### API 키 관리

1. ✅ `.env` 파일은 `.gitignore`에 포함
2. ✅ 환경 변수로 주입 (Docker secrets 권장)
3. ✅ API 키 노출 방지: 로그에 마스킹 처리
4. ❌ 코드에 하드코딩 절대 금지

### Docker Secrets (프로덕션 권장)

```bash
# secrets 생성
echo "sk-ant-your-key" | docker secret create anthropic_api_key -

# docker-compose.yml
services:
  monitoring-service:
    secrets:
      - anthropic_api_key
    environment:
      ANTHROPIC_API_KEY_FILE: /run/secrets/anthropic_api_key

secrets:
  anthropic_api_key:
    external: true
```

---

## 📈 다음 단계

### 고급 기능 추가

1. **스트리밍 응답**: Claude API의 streaming 기능 활용
2. **프롬프트 캐싱**: Prompt Caching으로 비용 절감
3. **배치 분석**: 여러 테스트 결과를 한 번에 분석
4. **A/B 비교**: 이전 결과와 현재 결과 비교 분석
5. **추세 분석**: 시간별/일별 성능 추세 AI 분석

### 모니터링 및 알림

1. **분석 실패 알림**: MCP 호출 실패 시 Slack 알림
2. **비용 추적**: Claude API 사용량 및 비용 모니터링
3. **성능 임계값**: AI가 권장한 임계값 자동 적용

---

## 📚 참고 자료

- [Anthropic Claude API 문서](https://docs.anthropic.com/en/api/getting-started)
- [MCP (Model Context Protocol) 사양](https://modelcontextprotocol.io/)
- [Admin Dashboard 가이드](./ADMIN_DASHBOARD_GUIDE.md)

---

**Generated with Claude Code** 🤖
