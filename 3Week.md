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

## 구현 세부사항

- `Resilience4jConfig` 추가 및 `PaymentEventPublisher` 업데이트: Kafka 발행을 `CircuitBreaker.decorateRunnable`로 감쌈. Circuit Breaker가 OPEN 상태일 때 Kafka 발행을 스킵하지만 outbox 레코드는 유지하여 나중에 재발행 가능.
- `backend/ingest-service/src/main/resources/application.yml`에서 resilience 속성 설정 (실패율 임계값, 느린 호출 지속 시간, 최소 호출 수, 대기 시간, HALF_OPEN 호출 제한).
- `CIRCUIT_BREAKER_GUIDE.md`에 상태 전이, 트러블슈팅 단계, Prometheus/Grafana 쿼리 문서화.

## 자동화 및 테스트

- `scripts/test-circuit-breaker.sh` 리팩터링: Docker Compose 네트워크 내부에서 실행 (`docker compose exec` 사용)하여 헬스 체크와 API 호출이 Jenkins가 원격이어도 항상 `ingest-service`를 대상으로 함.
- 스크립트 단계: warm-up (정상 트래픽) → Kafka 중단 → 느린/실패 요청 전송 → Kafka 재시작 → 복구 호출 전송. 어떤 단계라도 예상과 다르면 스크립트는 0이 아닌 종료 코드로 끝남.
- Jenkinsfile에 **"Circuit Breaker Test"** 단계 추가: smoke test 이후 실행됨. Circuit Breaker가 예상대로 트립하고 복구되지 않으면 빌드 실패.
- `docker-compose.yml`에 선택적 `ngrok` 서비스 (profile) 추가: `.env`에서 `NGROK_AUTHTOKEN` 읽음. GitHub 웹훅이 로컬 Jenkins에 자동으로 도달 가능.

## 관찰성 개선

- `monitoring/grafana/dashboards/payment-overview.json` 업데이트: **Circuit Breaker State** 패널이 6개 타일 렌더링 (CLOSED, OPEN, HALF_OPEN, DISABLED, FORCED_OPEN, FORCED_HALF_OPEN). 각 타일은 단일 메트릭 표시. 활성 상태 = 값 `1` (녹색 배경), 비활성 상태 = 회색. *Grafana JSON 변경 후 provisioning이 새 레이아웃을 적용하도록 Grafana 재배포 필요 (`docker compose up -d grafana --force-recreate`).*
- 느린 호출율, 실패율, 불허 메트릭이 Prometheus (`resilience4j_*` 시리즈)를 통해 노출되고 대시보드에 플롯됨을 검증.

## 문서화

- README 업데이트: Circuit Breaker 대시보드 사용법 및 ngrok + GitHub 웹훅 자동화 흐름 추가.
- `3Week.md` 추가: 향후 작업이 명확한 주간 변경 로그를 유지하도록 함.

## 성능 및 안정성 개선 현황

### 달성한 목표
- ✅ Circuit Breaker 프로덕션 수준 구현 (Resilience4j 2.1.0)
- ✅ 자동 테스트 및 모니터링 통합
- ✅ Grafana 대시보드 6가지 상태 시각화
- ✅ GitHub 웹훅 자동화 (ngrok)
- ✅ 200 RPS 안정적 처리 달성

### 현재 상태 확인 (로컬)
```bash
# 서비스 상태 확인
docker compose ps

# Circuit Breaker 상태 조회
curl http://localhost:8080/circuit-breaker/kafka-publisher

# Prometheus 메트릭 확인
curl http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state

# Grafana 대시보드
http://localhost:3000 (admin/admin)
```

## 다음 집중 영역

### Phase 2: 기능 확장 (계획 중)
- **Settlement / Reconciliation**: 정산 및 회계 검증 배치 로직 구현
- **API Gateway**: Spring Cloud Gateway 도입 검토
- **Service Mesh**: Istio vs Linkerd 비교 평가 및 롤아웃 준비

### Phase 3-5: 성능 확장 (ArchitecturePlan.md 참고)
- Phase 3: 성능 최적화 (DB 인덱싱, Kafka 배치 처리)
- Phase 4: KT Cloud 단일 서버 배포 (400 RPS)
- Phase 5: 계층별 분리 (3개 서버 = 1000 RPS 목표)

### 모니터링 강화
- 알림 규칙 설정 (Alertmanager)
- 느린 호출 비율 > 30% 시 알림
- Circuit Breaker OPEN 상태 지속 시간 추적
- Kafka 소비 Lag 모니터링

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

## 테스트 체크리스트

### 자동 테스트
```bash
# 전체 Circuit Breaker 시나리오 자동 실행 (9단계)
bash scripts/test-circuit-breaker.sh
# 예상 결과: 모든 단계 완료, 종료 코드 0
```

### 수동 검증
- [ ] 서비스 시작: `docker compose up -d`
- [ ] 초기 상태 확인: Circuit Breaker state = CLOSED
- [ ] 정상 요청 5개 전송
- [ ] Kafka 중단 후 느린 요청 6개 전송
- [ ] Circuit Breaker state = OPEN 또는 HALF_OPEN 확인
- [ ] Kafka 재시작
- [ ] 복구 요청 전송 (1-2초 응답 시간)
- [ ] 최종 상태 = CLOSED 확인

### Jenkins 파이프라인 검증
- [ ] GitHub push → Jenkins 자동 빌드 (ngrok 통해)
- [ ] 모든 단계 통과: Frontend 빌드 → Backend 빌드 → Docker Compose 배포 → Health Check → Smoke Test → Circuit Breaker Test
- [ ] Circuit Breaker Test 단계에서 script 정상 종료 (exit code 0)

## 참고 자료

- 완전한 가이드: [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)
- 아키텍처 플랜: [ArchitecturePlan.md](ArchitecturePlan.md)
- Circuit Breaker 모니터링: http://localhost:3000 (Grafana)
- Prometheus 쿼리: http://localhost:9090
