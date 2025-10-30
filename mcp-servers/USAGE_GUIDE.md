# MCP 서버 사용 가이드

## ✅ 설정 완료!

MCP 서버가 Claude Code에 설정되었습니다.

### 설정 파일 위치
```
.claude/mcp_settings.json
```

---

## 🚀 사용 방법

### 1. Docker Compose 실행 (필수)

MCP 서버가 접속할 서비스들을 먼저 실행해야 합니다:

```bash
cd Payment_SWElite
docker compose up -d
```

**확인**:
```bash
docker compose ps
```

필요한 서비스:
- ✅ mariadb (3306)
- ✅ redis (6379)
- ✅ kafka (9092)
- ✅ ingest-service (8080)
- ✅ prometheus (9090)

---

### 2. VS Code (Claude Code) 재시작

1. VS Code 완전히 종료
2. 다시 열기
3. Claude Code 세션 새로 시작

---

### 3. MCP 서버 활성화 확인

Claude Code와 대화에서:

```
"사용 가능한 MCP 도구 목록 보여줘"
```

또는

```
"서킷 브레이커 상태 확인해줘"
```

---

## 📊 MCP 도구 사용 예시

### Circuit Breaker MCP

**상태 확인**:
```
"서킷 브레이커 상태 확인해줘"
"Kafka 정상이야?"
```

**진단**:
```
"서킷 브레이커 문제 진단해줘"
"왜 서킷 브레이커가 열렸어?"
```

**실패 패턴 분석**:
```
"지난 1시간 동안 실패 패턴 분석해줘"
```

---

### Database Query MCP

**결제 조회**:
```
"지난 1시간 동안 실패한 결제 보여줘"
"오늘 완료된 결제 통계 보여줘"
"MERCHANT_X의 결제 내역 보여줘"
```

**원장 조회**:
```
"결제 번호 123의 원장 엔트리 보여줘"
"복식부기 균형 확인해줘"
```

**Outbox 이벤트**:
```
"5분 이상 발행 안 된 outbox 이벤트 있어?"
```

**정산**:
```
"오늘 결제 통계 보여줘"
"머천트별 거래량 상위 5개 보여줘"
```

---

### Redis Cache MCP

**Rate Limit 확인**:
```
"MERCHANT_X의 authorize rate limit 확인해줘"
"rate limit 80% 이상 사용 중인 머천트 찾아줘"
```

**캐시 상태**:
```
"Redis 캐시 통계 보여줘"
"Redis 메모리 사용량은?"
```

**멱등성 키 조회**:
```
"멱등성 키 ABC123 캐시에 있어?"
```

**운영 작업** (신중하게):
```
"MERCHANT_X의 rate limit 초기화해줘"
```

---

## 🎯 실전 시나리오

### 시나리오 1: 결제 실패 트러블슈팅

```
사용자: "결제 번호 12345가 처리 안 됐는데 왜 그래?"

Claude (자동으로 MCP 사용):
1. Database MCP → 결제 상태 조회
2. Outbox MCP → 이벤트 발행 여부 확인
3. Circuit Breaker MCP → Kafka 상태 확인
4. 종합 분석 및 해결 방안 제시
```

### 시나리오 2: 성능 문제 분석

```
사용자: "API가 느린데 원인이 뭐야?"

Claude (자동으로 MCP 사용):
1. Database MCP → 트래픽 통계 확인
2. Redis MCP → 캐시 히트율 확인
3. Circuit Breaker MCP → Kafka 레이턴시 확인
4. 병목 지점 식별 및 권장사항 제시
```

### 시나리오 3: Rate Limit 모니터링

```
사용자: "특정 머천트가 429 에러 받는다는데?"

Claude (자동으로 MCP 사용):
1. Redis MCP → 해당 머천트 rate limit 확인
2. Redis MCP → 한계치 근접 머천트 전체 조회
3. Database MCP → 최근 트래픽 패턴 분석
4. 정상 여부 판단 및 조치 방안 제시
```

---

## 🔧 트러블슈팅

### MCP 도구가 안 보여요

**1. VS Code 재시작**
- 완전히 종료 후 다시 실행

**2. 빌드 확인**
```bash
cd mcp-servers/circuit-breaker-mcp
ls dist/index.js  # 파일이 있어야 함

cd ../database-query-mcp
ls dist/index.js

cd ../redis-cache-mcp
ls dist/index.js
```

**3. 설정 파일 확인**
```bash
cat .claude/mcp_settings.json
```

경로가 정확한지 확인 (절대 경로, 백슬래시 두 번)

---

### 연결 오류

**Docker 서비스 확인**:
```bash
docker compose ps
```

**포트 확인**:
```bash
netstat -an | findstr "3306"  # MariaDB
netstat -an | findstr "6379"  # Redis
netstat -an | findstr "8080"  # Ingest Service
```

**로그 확인**:
```bash
docker compose logs ingest-service --tail=20
docker compose logs mariadb --tail=20
docker compose logs redis --tail=20
```

---

### 특정 도구만 안 돼요

**Circuit Breaker MCP**:
- Ingest Service 실행 확인: `curl http://localhost:8080/actuator/health`

**Database MCP**:
- MariaDB 접속 확인: `docker compose exec mariadb mysql -upayuser -ppaypass paydb -e "SELECT 1"`

**Redis MCP**:
- Redis 접속 확인: `docker compose exec redis redis-cli ping`

---

## 📚 추가 정보

### MCP 서버 소스 코드
- Circuit Breaker: `mcp-servers/circuit-breaker-mcp/src/index.ts`
- Database Query: `mcp-servers/database-query-mcp/src/index.ts`
- Redis Cache: `mcp-servers/redis-cache-mcp/src/index.ts`

### 재빌드 방법
```bash
cd mcp-servers/circuit-breaker-mcp
npm run build

cd ../database-query-mcp
npm run build

cd ../redis-cache-mcp
npm run build
```

### 개발 모드 (빌드 없이 실행)
```bash
cd mcp-servers/circuit-breaker-mcp
npm run dev
```

---

## 🎉 이제 사용하세요!

Claude Code와 대화하면서 자연어로 시스템을 모니터링하고 디버깅하세요.

**예시**:
```
"전체 시스템 헬스 체크해줘"
"오늘 성능 요약 보고서 만들어줘"
"이상한 패턴 있으면 알려줘"
```

Claude가 자동으로 적절한 MCP 도구들을 사용해서 답변합니다!
