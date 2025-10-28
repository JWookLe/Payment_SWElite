# 3주차 요약

## Circuit Breaker가 필요한 이유

`backend/ingest-service` 내부의 Kafka 퍼블리셔는 다운스트림 장애에서 살아남아야 함. Circuit Breaker는 발행 호출을 감싸고 6가지 상태를 거쳐감:

| 상태 | 트리거 | 동작 |
| --- | --- | --- |
| **CLOSED** | 정상 작동 | 모든 호출이 통과함. 메트릭에 성공/실패 횟수 기록 |
| **OPEN** | 실패율 또는 느린 호출율이 설정된 임계값 초과 | 새로운 호출이 즉시 실패 (`CallNotPermittedException` 발생), Kafka와 애플리케이션 스레드 풀 보호 |
| **HALF_OPEN** | OPEN 상태의 대기 시간 만료 | 제한된 수의 테스트 호출만 허용됨. 성공하면 CLOSED로 전환, 실패하면 OPEN으로 돌아감 |
| **DISABLED** | Circuit Breaker 모니터링 비활성화 (프로덕션에서 거의 사용 안 함) | 트래픽은 통과하지만 메트릭은 계속 누적됨 |
| **FORCED_OPEN / FORCED_HALF_OPEN** | 운영자 수동 개입 | 조사 또는 디버깅 중에 Circuit Breaker를 열린 상태로 유지하기 위해 사용 |

임계값을 튜닝했으므로 느린 호출(>5초) 또는 실패율 ≥50%일 때 Circuit Breaker가 빠르게 열리지만, 정상 트래픽은 수동 개입 없이 다시 닫힘.

## Circuit Breaker 구현 세부사항

- `Resilience4jConfig` 추가 및 `PaymentEventPublisher` 업데이트: Kafka 발행을 `CircuitBreaker.decorateRunnable`로 감쌈. Circuit Breaker가 OPEN 상태일 때 Kafka 발행을 스킵하지만 outbox 레코드는 유지하여 나중에 재발행 가능.
- `backend/ingest-service/src/main/resources/application.yml`에서 resilience 속성 설정 (실패율 임계값, 느린 호출 지속 시간, 최소 호출 수, 대기 시간, HALF_OPEN 호출 제한).
- `CIRCUIT_BREAKER_GUIDE.md`에 상태 전이, 트러블슈팅 단계, Prometheus/Grafana 쿼리 문서화.
- Circuit Breaker 상태를 Eureka Health Indicator로 노출하여 서비스 레지스트리에서 상태 추적 가능.

## Service Discovery (Spring Cloud Eureka) 도입

마이크로서비스 아키텍처를 위한 중앙 집중식 서비스 레지스트리 구현:

### Eureka Server 구현
- 새로운 모듈 `backend/eureka-server` 추가 (Spring Cloud Netflix Eureka Server 4.1.1)
- `EurekaServerApplication`에 `@EnableEurekaServer` 적용
- Self-preservation 비활성화 (개발 환경) 및 Health/Metrics 엔드포인트 노출
- Docker Compose에서 포트 8761로 실행

### Eureka Client 설정
- **ingest-service**: Eureka에 자동 등록 (IP 기반 선호)
  - `eureka.client.register-with-eureka: true`
  - `eureka.client.fetch-registry: true`
  - `eureka.instance.prefer-ip-address: true`
- **consumer-worker**: 동일한 Eureka Client 설정으로 등록
- docker-compose.yml에서 `EUREKA_SERVER_URL` 환경 변수로 Eureka 서버 주소 전달

### 모니터링 및 관리
- Eureka 대시보드: http://localhost:8761
- 등록된 서비스: `/eureka/apps` API로 조회 가능
- 서비스 상태: Healthy/Down 상태 실시간 추적
- Prometheus 메트릭 노출 (Health Indicator와 함께 작동)

### Phase 5 확장성
- Server 1 (API Layer): ingest-service 등록 → Eureka 조회로 downstream 서비스 발견
- Server 2 (Data Layer): consumer-worker 등록
- Server 3 (Infra): eureka-server 중앙 운영 → 3개 서버 간 느슨한 결합
- API Gateway 도입 시 Eureka를 통해 동적 라우팅 가능

## 자동화 및 테스트

- `scripts/test-circuit-breaker.sh` 리팩터링: Docker Compose 네트워크 내부에서 실행 (`docker compose exec` 사용)하여 헬스 체크와 API 호출이 Jenkins가 원격이어도 항상 `ingest-service`를 대상으로 함.
- 스크립트 단계: warm-up (정상 트래픽) → Kafka 중단 → 느린/실패 요청 전송 → Kafka 재시작 → 복구 호출 전송. 어떤 단계라도 예상과 다르면 스크립트는 0이 아닌 종료 코드로 끝남.
- Jenkinsfile에 **"Circuit Breaker Test"** 단계 추가: smoke test 이후 실행됨. Circuit Breaker가 예상대로 트립하고 복구되지 않으면 빌드 실패.
- `docker-compose.yml`에 선택적 `ngrok` 서비스 (profile) 추가: `.env`에서 `NGROK_AUTHTOKEN` 읽음. GitHub 웹훅이 로컬 Jenkins에 자동으로 도달 가능.

## 관찰성 개선

- `monitoring/grafana/dashboards/payment-overview.json` 업데이트: **Circuit Breaker State** 패널이 6개 타일 렌더링 (CLOSED, OPEN, HALF_OPEN, DISABLED, FORCED_OPEN, FORCED_HALF_OPEN). 각 타일은 단일 메트릭 표시. 활성 상태 = 값 `1` (녹색 배경), 비활성 상태 = 회색. *Grafana JSON 변경 후 provisioning이 새 레이아웃을 적용하도록 Grafana 재배포 필요 (`docker compose up -d grafana --force-recreate`).*
- 느린 호출율, 실패율, 불허 메트릭이 Prometheus (`resilience4j_*` 시리즈)를 통해 노출되고 대시보드에 플롯됨을 검증.
- Eureka 서버도 메트릭 노출하여 Prometheus에서 서비스 레지스트리 상태 추적 가능.

## 문서화

- README 업데이트: Circuit Breaker와 Eureka Service Discovery 사용법 및 ngrok + GitHub 웹훅 자동화 흐름 추가.
- `3Week.md` 업데이트: 주간 변경 로그로 Circuit Breaker와 Eureka 도입 상황 정리.

## 성능 및 안정성 개선 현황

### 달성한 목표
- ✅ Circuit Breaker 프로덕션 수준 구현 (Resilience4j 2.1.0)
- ✅ 자동 테스트 및 모니터링 통합 (9단계 시나리오)
- ✅ Grafana 대시보드 6가지 상태 시각화
- ✅ GitHub 웹훅 자동화 (ngrok)
- ✅ Spring Cloud Eureka Service Discovery 도입
- ✅ 200 RPS 안정적 처리 달성

### 현재 상태 확인 (로컬)
```bash
# 서비스 상태 확인
docker compose ps

# Eureka 대시보드 (서비스 레지스트리)
http://localhost:8761

# Circuit Breaker 상태 조회
curl http://localhost:8080/circuit-breaker/kafka-publisher

# 등록된 서비스 확인
curl http://localhost:8761/eureka/apps

# Prometheus 메트릭 확인
curl http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state

# Grafana 대시보드
http://localhost:3000 (admin/admin)
```

## 다음 집중 영역

### Phase 4: API Gateway & Load Balancing (계획 중)
- **Spring Cloud Gateway**: 마이크로서비스 라우팅 및 필터링
- **Eureka 기반 동적 라우팅**: 서비스 인스턴스 자동 발견
- **Load Balancer (Ribbon/Spring Cloud LoadBalancer)**: 클라이언트 사이드 로드 밸런싱

### Phase 5: Service Mesh (계획 중)
- **Istio vs Linkerd**: 비교 평가 및 선택
- **트래픽 관리**: Virtual Service, Destination Rule
- **보안**: mTLS, Authorization Policy
- **모니터링**: 분산 추적 (Jaeger), 메트릭 수집 (Kiali)

### Phase 3-5: 성능 확장 (ArchitecturePlan.md 참고)
- Phase 3: 성능 최적화 (DB 인덱싱, Kafka 배치 처리)
- Phase 4: KT Cloud 단일 서버 배포 (400 RPS)
- Phase 5: 계층별 분리 (3개 서버 = 1000 RPS 목표)

### 모니터링 강화
- 알림 규칙 설정 (Alertmanager)
- Circuit Breaker OPEN 상태 지속 시간 추적
- Eureka 서비스 등록/해제 모니터링
- Kafka 소비 Lag 및 처리량 모니터링
- 느린 호출 비율 > 30% 시 알림

## 주의사항

### Circuit Breaker 튜닝
- `failureRateThreshold: 50%`: 실패율 50% 이상 시 OPEN
- `slowCallDurationThreshold: 5000ms`: 5초 이상 응답 시간을 느린 호출로 판정
- `minimumNumberOfCalls: 5`: 최소 5개 호출 후 상태 판정 (과민 반응 방지)
- `waitDurationInOpenState: 30s`: OPEN → HALF_OPEN 대기 시간

### Kafka 타임아웃 설정
```yaml
spring.kafka.producer:
  request-timeout-ms: 10000  # 요청 타임아웃: 10초
  delivery-timeout-ms: 15000 # 배송 타임아웃: 15초
  acks: all                   # 모든 replica에서 승인 대기
  retries: 1                  # 실패 시 1회 재시도
```

### Eureka Client 배포 팁
- Eureka Server가 먼저 시작되어야 클라이언트가 등록 가능
- docker-compose.yml에서 `depends_on` 사용하여 시작 순서 보장
- Eureka Server 비정상: 클라이언트는 로컬 캐시된 레지스트리 사용 (자가 보호)

## 테스트 체크리스트

### 자동 테스트
```bash
# 전체 Circuit Breaker 시나리오 자동 실행 (9단계)
bash scripts/test-circuit-breaker.sh
# 예상 결과: 모든 단계 완료, 종료 코드 0
```

### 수동 검증 - Circuit Breaker
- [ ] 서비스 시작: `docker compose up -d`
- [ ] 초기 상태 확인: Circuit Breaker state = CLOSED
- [ ] 정상 요청 5개 전송
- [ ] Kafka 중단 후 느린 요청 6개 전송
- [ ] Circuit Breaker state = OPEN 또는 HALF_OPEN 확인
- [ ] Kafka 재시작
- [ ] 복구 요청 전송 (1-2초 응답 시간)
- [ ] 최종 상태 = CLOSED 확인

### 수동 검증 - Eureka
- [ ] Eureka 대시보드 접속: http://localhost:8761
- [ ] ingest-service 등록 확인 (UP 상태)
- [ ] consumer-worker 등록 확인 (UP 상태)
- [ ] 서비스 내려보기 (`docker compose stop ingest-service`)
- [ ] Eureka에서 DOWN 상태 변경 확인 (30초 이내)
- [ ] 서비스 올려보기 (`docker compose start ingest-service`)
- [ ] Eureka에서 UP 상태 복구 확인

### Jenkins 파이프라인 검증
- [ ] GitHub push → Jenkins 자동 빌드 (ngrok 통해)
- [ ] 모든 단계 통과: Frontend 빌드 → Backend 빌드 → Docker Compose 배포 → Health Check → Smoke Test → Circuit Breaker Test
- [ ] Circuit Breaker Test 단계에서 script 정상 종료 (exit code 0)
- [ ] Eureka Server 시작 확인 (docker compose logs pay-eureka)

## 참고 자료

- 완전한 가이드: [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)
- Eureka 설정: `backend/eureka-server/src/main/resources/application.yml`
- 아키텍처 플랜: [ArchitecturePlan.md](ArchitecturePlan.md)
- Circuit Breaker 모니터링: http://localhost:3000 (Grafana)
- Eureka 대시보드: http://localhost:8761
- Prometheus 쿼리: http://localhost:9090

## 용어 정리

- **Service Discovery**: 마이크로서비스가 서로의 위치(IP, 포트)를 자동 발견
- **Eureka Server**: 서비스 등록소 (중앙 레지스트리)
- **Eureka Client**: 서비스 자신을 Eureka에 등록하고 다른 서비스 조회
- **Health Check**: 주기적 heartbeat로 서비스 상태 모니터링
- **Self-Preservation**: Eureka Server가 인스턴스 손실 감지 시 보호 모드 (프로덕션)
