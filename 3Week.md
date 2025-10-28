# 3주차 작업 요약

## 1. Circuit Breaker (Resilience4j) 구현

### 필요성
Kafka 퍼블리셔가 장애를 만나도 전체 시스템을 보호해야 하기 때문에 Circuit Breaker 도입.

### 구현 내용
- **Resilience4j 2.1.0** 기반 Circuit Breaker
- `PaymentEventPublisher`에서 Kafka 발행 보호
- 상태: CLOSED → OPEN → HALF_OPEN → CLOSED
- 설정:
  - 실패율/느린호출율 >= 50% → OPEN
  - 느린호출 판정 >= 5초
  - 최소 5개 호출 후 판정
  - OPEN → HALF_OPEN 대기: 30초
  - HALF_OPEN 테스트: 최대 3개 요청

### 구현 파일
- `backend/ingest-service/src/main/java/com/example/payment/service/PaymentEventPublisher.java`
- `backend/ingest-service/src/main/java/com/example/payment/config/Resilience4jConfig.java`
- `backend/ingest-service/src/main/resources/application.yml` (L82-95)
- `backend/ingest-service/src/main/java/com/example/payment/web/CircuitBreakerStatusController.java`

### 모니터링
```bash
# Circuit Breaker 상태 조회
curl http://localhost:8080/circuit-breaker/kafka-publisher

# Prometheus 메트릭
http://localhost:9090/graph → resilience4j_circuitbreaker_state

# Grafana 대시보드
http://localhost:3000 → Payment Service Overview → Circuit Breaker State 패널
```

### 자동 테스트
```bash
bash scripts/test-circuit-breaker.sh
# 9단계 자동 시나리오: warm-up → Kafka 중단 → 느린 요청 → Kafka 재시작 → 복구
```

---

## 2. Service Discovery (Eureka) 도입

### 필요성
마이크로서비스 확장(Phase 5)을 대비해 서비스 간 자동 발견 메커니즘 필요.

### 구현 내용

#### Eureka Server
- 새로운 모듈: `backend/eureka-server`
- Spring Cloud Netflix Eureka Server 4.1.1
- 포트: 8761
- Self-preservation 비활성화 (개발 환경)
- Health/Metrics 엔드포인트 노출 (Prometheus 연동)

#### Eureka Client
- **ingest-service**: 자동 등록 (IP 기반)
- **consumer-worker**: 자동 등록 (IP 기반)
- 설정:
  - `register-with-eureka: true`
  - `fetch-registry: true`
  - `prefer-ip-address: true`
  - Heartbeat: 30초 주기

#### docker-compose.yml
- eureka-server 서비스 추가 (포트 8761)
- ingest-service/consumer-worker에서 `EUREKA_SERVER_URL` 환경 변수 설정
- depends_on으로 시작 순서 보장

### 구현 파일
- `backend/eureka-server/src/main/java/com/example/eureka/EurekaServerApplication.java`
- `backend/eureka-server/src/main/resources/application.yml`
- `backend/ingest-service/src/main/resources/application.yml` (L106-114)
- `backend/consumer-worker/src/main/resources/application.yml` (L48-55)
- `backend/eureka-server/build.gradle.kts` (spring-cloud-starter-netflix-eureka-server 4.1.1)
- `backend/ingest-service/build.gradle.kts` (spring-cloud-starter-netflix-eureka-client 4.1.1)
- `backend/consumer-worker/build.gradle.kts` (spring-cloud-starter-netflix-eureka-client 4.1.1)

### 확인 방법
```bash
# Eureka 대시보드
http://localhost:8761

# 등록된 서비스 조회
curl http://localhost:8761/eureka/apps
curl http://localhost:8761/eureka/apps/INGEST-SERVICE
curl http://localhost:8761/eureka/apps/CONSUMER-WORKER

# Prometheus 메트릭
http://localhost:9090 → eureka 관련 메트릭 확인
```

---

## 3. Jenkins & GitHub Webhook 자동화

### 구현 내용
- Jenkins 파이프라인에 **"Circuit Breaker Test"** 단계 추가 (Smoke Test 이후)
- ngrok 프로필 추가: `docker compose --profile ngrok up -d`
- `.env` 파일에 `NGROK_AUTHTOKEN` 설정
- GitHub Webhook: `https://<ngrok-url>/github-webhook/`

### 파이프라인 단계
1. 소스 체크아웃
2. Frontend 빌드 (npm)
3. Backend 빌드 (Gradle)
4. Docker Compose 배포
5. Health Check
6. Smoke Test
7. **Circuit Breaker Test** (자동 스크립트)

### 테스트 스크립트
- `scripts/test-circuit-breaker.sh` 리팩터링
- Docker Compose 네트워크 내부 실행 (`docker compose exec`)
- 9단계 자동 검증

---

## 4. Grafana 대시보드 강화

### Circuit Breaker 패널
- 상태 타일 6개: CLOSED / OPEN / HALF_OPEN / DISABLED / FORCED_OPEN / FORCED_HALF_OPEN
- 활성 상태만 초록색
- 느린호출 비율 & 실패율 Stat
- 호출 수 추이 Time Series

### 구현 파일
- `monitoring/grafana/dashboards/payment-overview.json`

### Grafana 접속
```bash
http://localhost:3000 (admin/admin)
Dashboards → Payment Service Overview
```

---

## 5. 문서화

### 작성/수정된 문서
- `README.md`: Circuit Breaker & Eureka 섹션 추가
- `CIRCUIT_BREAKER_GUIDE.md`: 완전한 가이드 (524줄)
- `3Week.md`: 주간 변경 로그
- `docker-compose.yml`: eureka-server 서비스 추가

### 주요 내용
- Circuit Breaker 상태 전이 다이어그램
- Eureka 등록/조회 프로세스
- 수동/자동 테스트 방법
- Phase 5 확장 계획

---

## 6. 달성한 목표

- ✅ Circuit Breaker 프로덕션 수준 구현
- ✅ Eureka Service Discovery 완전 통합
- ✅ 자동화된 테스트 스크립트 (9단계)
- ✅ GitHub Webhook + Jenkins 연동
- ✅ 실시간 모니터링 (Prometheus/Grafana)
- ✅ 200 RPS 안정적 처리
- ✅ Phase 5 스케일링 준비 완료

---

## 7. 다음 작업

### Phase 4: API Gateway & Load Balancing
- Spring Cloud Gateway 도입
- Eureka 기반 동적 라우팅
- Client-side 로드 밸런싱 (LoadBalancer)

### Phase 5: Service Mesh
- Istio vs Linkerd 평가
- 트래픽 관리 (Virtual Service, Destination Rule)
- 보안 (mTLS, Authorization Policy)
- 모니터링 (Jaeger, Kiali)

### 성능 확장
- Phase 3: DB 인덱싱, Kafka 배치 처리
- Phase 4: KT Cloud 단일 서버 배포 (400 RPS)
- Phase 5: 3개 서버 계층 분리 (1000 RPS)

---

## 8. 테스트 체크리스트

### Circuit Breaker
- [ ] 서비스 시작: `docker compose up -d`
- [ ] Eureka 대시보드 확인: http://localhost:8761
- [ ] 정상 요청 5개 전송
- [ ] Kafka 중단 후 느린 요청 6개
- [ ] Circuit Breaker state = OPEN/HALF_OPEN 확인
- [ ] Kafka 재시작 후 복구 확인
- [ ] 최종 상태 = CLOSED

### Eureka
- [ ] ingest-service UP 상태 확인
- [ ] consumer-worker UP 상태 확인
- [ ] `/eureka/apps` API 응답 확인
- [ ] 서비스 내렸다 다시 올려서 상태 변경 확인 (30초 이내)

### Jenkins
- [ ] GitHub push → 자동 빌드 (ngrok 통해)
- [ ] 모든 단계 통과
- [ ] Circuit Breaker Test 정상 완료
- [ ] Eureka Server 시작 확인

---

## 9. 참고 자료

- [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md) - 완전한 가이드
- [ArchitecturePlan.md](ArchitecturePlan.md) - 성능 확장 로드맵
- [README.md](README.md) - Circuit Breaker & Eureka 섹션
- Eureka Dashboard: http://localhost:8761
- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090

---

## 10. 용어

- **Circuit Breaker**: 장애 전파 방지 (Fail-fast)
- **Service Discovery**: 서비스 자동 발견
- **Eureka Server**: 중앙 레지스트리
- **Eureka Client**: 서비스 자신을 등록하는 클라이언트
- **Health Check**: heartbeat로 서비스 상태 모니터링
- **Self-Preservation**: Eureka 자가 보호 모드 (프로덕션)
