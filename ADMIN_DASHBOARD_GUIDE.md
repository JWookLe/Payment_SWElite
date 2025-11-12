# 운영 관리자 대시보드 (Admin Dashboard)

## 개요

8가지 테스트를 버튼 클릭만으로 실행하고, AI가 자동으로 분석한 보고서를 생성하는 전문적인 관리자 페이지입니다.

### 주요 기능

1. **원클릭 테스트 실행**: 8가지 테스트를 버튼 하나로 실행
2. **AI 자동 분석**: 테스트 결과를 AI가 분석하여 요약 및 권장사항 제시
3. **실시간 진행 상태**: 테스트 실행 중 로딩 상태 표시
4. **보고서 히스토리**: 과거 테스트 결과 조회 및 비교
5. **Raw Data 다운로드**: JSON 형식으로 원시 데이터 내보내기

---

## 접속 방법

```
http://localhost:5173/admin
```

메인 Commerce 페이지에서 상단 네비게이션의 **"⚙️ Admin Dashboard"** 클릭

---

## 사용 가능한 테스트

### 📊 부하 테스트 (Load Testing)

#### 1. K6: 승인 전용
- **설명**: 승인 API 부하 테스트 (최대 400 RPS)
- **예상 시간**: 8분
- **실행 내용**:
  - Warm-up: 100 RPS (30초)
  - Ramp-up: 200 RPS (1분)
  - Increase: 300 RPS (2분)
  - Target: 400 RPS (4분)
  - Cool-down (30초)

#### 2. K6: 전체 플로우
- **설명**: 승인 + 정산 + 환불 전체 플로우 테스트
- **예상 시간**: 10분
- **실행 내용**: 승인 → 정산 → 환불 순차 실행 부하 테스트

---

### 🛡️ 안정성 테스트 (Resilience)

#### 3. Circuit Breaker
- **설명**: Kafka 다운타임 시뮬레이션 및 복구 검증
- **예상 시간**: 2분
- **실행 내용**:
  1. 정상 요청 5건 (Kafka UP)
  2. Kafka 중단
  3. Slow call 6건 → Circuit OPEN 확인
  4. Kafka 재시작
  5. 복구 요청 1건 → Circuit CLOSED 확인

---

### 📈 모니터링 (Monitoring)

#### 4. Health Check
- **설명**: 모든 서비스 헬스 체크 (DB, Redis, Kafka)
- **예상 시간**: 30초
- **확인 항목**:
  - Eureka Server (8761)
  - Ingest Service (8080)
  - Monitoring Service (8082)
  - MariaDB, Redis, Kafka 연결 상태

#### 5. Database 통계
- **설명**: DB 연결, 쿼리 성능, 테이블 통계
- **예상 시간**: 15초
- **확인 항목**:
  - Connection pool 상태
  - 테이블별 레코드 수
  - 인덱스 성능

#### 6. Redis 통계
- **설명**: Cache hit/miss rate, 메모리 사용량
- **예상 시간**: 15초
- **확인 항목**:
  - Cache hit/miss ratio
  - 저장된 키 개수
  - 메모리 사용률

#### 7. Kafka 통계
- **설명**: Topic lag, consumer group 상태
- **예상 시간**: 20초
- **확인 항목**:
  - Topic별 lag
  - Consumer group offset
  - Partition 상태

---

### 💰 비즈니스 메트릭

#### 8. Settlement 통계
- **설명**: 정산 완료율, 금액 집계, 실패 케이스
- **예상 시간**: 10초
- **확인 항목**:
  - 정산 성공/실패 건수
  - 총 정산 금액
  - 실패 사유 분석

---

## 사용 방법

### 1. 테스트 실행

1. Admin 대시보드 접속 (`http://localhost:5173/admin`)
2. 실행하고 싶은 테스트 카드에서 **"테스트 실행"** 버튼 클릭
3. 실행 중에는 버튼이 **"실행 중..."**으로 표시되며 로딩 스피너 표시
4. 완료 시 상단에 성공/실패 메시지 표시

### 2. AI 보고서 확인

테스트 완료 후 자동으로 생성되는 보고서 포함:

- **AI 분석 요약**: 테스트 결과에 대한 AI의 자동 분석
- **주요 메트릭**: 핵심 성능 지표 (Total Requests, Success Rate, P95 Duration 등)
- **개선 권장사항**: AI가 분석한 성능 개선 포인트

### 3. 보고서 보기 및 숨기기

- **"보고서 보기 ▼"** 버튼 클릭 → 상세 보고서 펼치기
- **"접기 ▲"** 버튼 클릭 → 보고서 접기

### 4. Raw Data 다운로드

- 보고서 하단의 **"Raw Data 다운로드"** 버튼 클릭
- JSON 파일로 원시 데이터 저장 (`{testId}-{timestamp}.json`)

### 5. 전체 보고서 내보내기

- 우측 상단 **"📥 전체 보고서 내보내기"** 버튼 클릭
- 모든 테스트 결과를 하나의 JSON 파일로 내보내기

---

## AI 보고서 생성 아키텍처

```
User → Admin UI → Backend API → 테스트 실행
                              ↓
                         결과 수집
                              ↓
                    AI 분석 엔진 (MCP 통합 준비)
                              ↓
                    보고서 생성 & 저장
                              ↓
                    Frontend에 자동 표시
```

### 현재 구현 상태

- ✅ **테스트 실행 오케스트레이션**: AdminTestService가 8가지 테스트 관리
- ✅ **결과 수집 및 파싱**: K6 summary.json, shell output 파싱
- ✅ **기본 AI 분석**: 로컬 분석 로직 (메트릭 추출, 권장사항 생성)
- 🔄 **MCP 서버 통합 (준비 완료)**: `generateAIAnalysis()` 메서드에서 MCP 호출 가능

### MCP 통합 확장 방법

`AdminTestService.java`의 `generateAIAnalysis()` 메서드를 수정하여 실제 MCP 서버 호출:

```java
private String generateAIAnalysis(String testId, Map<String, Object> rawData) {
    // MCP 서버 호출 예시
    try {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = new HashMap<>();
        request.put("testId", testId);
        request.put("rawData", rawData);
        request.put("prompt", "Analyze this test result and provide summary with recommendations");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "http://localhost:3000/mcp/analyze-test",
            entity,
            Map.class
        );

        return (String) response.getBody().get("analysis");
    } catch (Exception e) {
        logger.error("Failed to call MCP server", e);
        return generateLocalAnalysis(rawData); // Fallback
    }
}
```

---

## 파일 구조

### Frontend

```
frontend/src/
├── AdminPage.jsx          # Admin 대시보드 메인 컴포넌트
├── admin-styles.css       # Admin 페이지 전용 스타일
└── main.jsx               # 라우팅 설정 (/, /admin)
```

### Backend

```
backend/monitoring-service/src/main/java/com/example/monitoring/
├── controller/
│   └── AdminTestController.java    # Admin API 엔드포인트
├── service/
│   └── AdminTestService.java       # 테스트 실행 및 AI 분석
├── dto/
│   └── TestReportDTO.java          # 보고서 DTO
└── config/
    └── AppConfig.java               # RestTemplate, ObjectMapper Bean
```

### API 엔드포인트

```
POST /api/admin/tests/k6/authorize-only     # K6 승인 테스트
POST /api/admin/tests/k6/full-flow          # K6 전체 플로우
POST /api/admin/tests/circuit-breaker       # Circuit Breaker 테스트
POST /api/admin/tests/health-check          # Health Check
POST /api/admin/tests/database-stats        # Database 통계
POST /api/admin/tests/redis-stats           # Redis 통계
POST /api/admin/tests/kafka-stats           # Kafka 통계
POST /api/admin/tests/settlement-stats      # Settlement 통계

GET  /api/admin/reports/recent              # 최근 보고서 조회
GET  /api/admin/reports/history/{testId}    # 테스트별 히스토리
GET  /api/admin/reports/{reportId}          # 보고서 상세 조회
```

---

## 빌드 및 실행

### 1. 의존성 설치

```bash
cd frontend
npm install
```

### 2. Backend 빌드

```bash
./gradlew :backend:monitoring-service:build
```

### 3. Docker Compose 실행

```bash
docker compose up -d
```

### 4. Frontend 개발 서버 실행

```bash
cd frontend
npm run dev
```

### 5. 접속

- **Commerce**: http://localhost:5173/
- **Admin Dashboard**: http://localhost:5173/admin

---

## 트러블슈팅

### 1. "react-router-dom not found" 에러

```bash
cd frontend
npm install react-router-dom
```

### 2. 테스트 실행 시 "Permission denied" 에러

```bash
chmod +x scripts/run-k6-test.sh
chmod +x scripts/test-circuit-breaker.sh
```

### 3. AI 분석이 "분석 중..."으로 표시

- `AdminTestService.generateAIAnalysis()` 메서드 로그 확인
- MCP 서버 연결 상태 확인 (준비 시)

### 4. Gateway 404 에러

- Gateway가 `/api/admin/**` 경로를 라우팅하는지 확인
- `backend/gateway/application.yml`에 라우팅 규칙 추가 필요

---

## 향후 개선 계획

### 단기 (1-2주)

- [ ] MCP 서버 실제 통합 (Claude API 호출)
- [ ] WebSocket 기반 실시간 진행 상태 스트리밍
- [ ] 보고서 DB 영구 저장 (현재 in-memory)
- [ ] 테스트 스케줄링 (cron)

### 중기 (1개월)

- [ ] 대시보드 차트 시각화 (Chart.js, Recharts)
- [ ] 알람 설정 (임계값 초과 시 Slack/Email 알림)
- [ ] A/B 테스트 비교 기능
- [ ] 커스텀 테스트 시나리오 업로드

### 장기 (3개월+)

- [ ] ML 기반 성능 예측 및 이상 감지
- [ ] Multi-tenant 지원 (팀별 권한 관리)
- [ ] Grafana 대시보드 임베딩
- [ ] Chaos Engineering 시나리오 추가

---

## 문의

기술 문의 및 버그 리포트:
- GitHub Issues: `https://github.com/your-repo/issues`
- Email: `admin@payment-swelite.com`

---

**Generated with Claude Code** 🤖
