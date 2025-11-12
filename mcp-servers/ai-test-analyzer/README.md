# AI Test Analyzer MCP Server

Claude API를 활용하여 테스트 결과를 자동으로 분석하는 MCP 서버입니다.

## 기능

- **K6 부하 테스트 분석**: 성능 메트릭, 병목 지점, 권장사항 제공
- **Circuit Breaker 테스트 분석**: 상태 전환, 복구 동작 평가
- **Health Check 분석**: 서비스별 상태 및 의존성 평가
- **모니터링 통계 분석**: DB/Redis/Kafka/Settlement 통계 분석

## 설치

```bash
cd mcp-servers/ai-test-analyzer
npm install
npm run build
```

## 사용법

### 1. API 키 설정

``bash
export ANTHROPIC_API_KEY=your_anthropic_api_key_here
```

또는 프로젝트 루트의 `.env` 파일에 추가:

```
ANTHROPIC_API_KEY=sk-ant-...
```

### 2. 서버 실행

```bash
npm start
```

### 3. Java에서 호출

`MCPAnalysisService`가 자동으로 MCP 서버를 호출합니다.

## 프롬프트 엔지니어링

각 테스트 타입별로 최적화된 프롬프트를 사용합니다:

### K6 부하 테스트 프롬프트

- **분석 항목**: HTTP 요청 성공률, 응답 시간 분포, Throughput
- **메트릭**: avg, p95, p99 response times
- **권장사항**: 성능 최적화, 스케일링 전략

### Circuit Breaker 프롬프트

- **분석 항목**: 상태 전환 (CLOSED → OPEN → HALF_OPEN)
- **메트릭**: Slow call rate, not permitted calls
- **권장사항**: Circuit breaker 임계값 조정

### Health Check 프롬프트

- **분석 항목**: 서비스별 UP/DOWN 상태
- **메트릭**: DB, Redis, Kafka 연결 상태
- **권장사항**: 즉시 조치 필요한 항목

## 응답 형식

```json
{
  "aiSummary": "=== AI 자동 분석 보고서 ===\n\n...",
  "metrics": {
    "Total Requests": "24000",
    "Success Rate": "99.5%",
    "Avg Duration": "127.34 ms",
    "P95 Duration": "245.67 ms"
  },
  "recommendations": [
    "P95 응답 시간이 1초를 초과합니다. 성능 최적화를 권장합니다.",
    "Database connection pool을 증가시키세요."
  ]
}
```

## 환경 변수

| 변수 | 설명 | 필수 |
|------|------|------|
| `ANTHROPIC_API_KEY` | Claude API 키 | Yes |
| `ANTHROPIC_MODEL` | 사용할 모델 (기본: claude-3-5-sonnet-20241022) | No |

## 트러블슈팅

### "ANTHROPIC_API_KEY not set" 에러

```bash
export ANTHROPIC_API_KEY=sk-ant-your-key-here
```

### 빌드 에러

```bash
rm -rf node_modules dist
npm install
npm run build
```

### MCP 서버가 응답하지 않음

1. Node.js가 설치되어 있는지 확인 (`node --version`)
2. 빌드가 완료되었는지 확인 (`ls dist/`)
3. 로그 확인: stderr에 출력됩니다

## 개발

### 테스트 실행

```bash
# MCP 서버 직접 테스트
echo '{"method":"tools/list"}' | node dist/index.js

# 분석 도구 호출 테스트
echo '{
  "method": "tools/call",
  "params": {
    "name": "analyze_k6_test",
    "arguments": {
      "testId": "test-1",
      "scenario": "authorize-only",
      "rawData": { "exitCode": 0 }
    }
  }
}' | ANTHROPIC_API_KEY=your_key node dist/index.js
```

### 프롬프트 수정

`src/index.ts`의 `buildK6AnalysisPrompt()` 등의 함수를 수정하세요.

## 라이선스

MIT
