# 3주차 작업 회고

## 이번 주에 집중한 이유
- Kafka 퍼블리셔가 장애를 만나도 버텨야 해서 회로 차단기(Circuit Breaker)를 실제 환경 수준으로 끌어올렸습니다.
- Jenkins 파이프라인과 Grafana 대시보드를 묶어 둬서, 문제가 생기면 바로 눈에 띄도록 만들고 싶었습니다.
- 서비스가 하나둘 늘어날 때를 대비해 Eureka 기반 서비스 디스커버리를 미리 붙였습니다.

## 구현 요약
- `backend/ingest-service`의 `PaymentEventPublisher`를 Resilience4j Circuit Breaker로 감싸고, 실패율/느린 호출 임계값을 `application.yml`에서 관리하도록 했습니다.
- 신규 모듈 `backend/eureka-server`를 추가해 Spring Cloud Netflix Eureka 서버를 띄웠고, ingest-service와 consumer-worker가 클라이언트로 등록되도록 설정을 추가했습니다.
- Circuit Breaker 자동화 스크립트(`scripts/test-circuit-breaker.sh`)를 Docker Compose 네트워크 안에서 돌도록 손봤고, Jenkins 파이프라인에 “Circuit Breaker Test” 단계를 넣어 매 빌드마다 실행되게 했습니다.
- Grafana `payment-overview.json`을 수정해 회로 차단기 상태 타일을 6개(CLOSED/OPEN/HALF_OPEN/DISABLED/FORCED_OPEN/FORCED_HALF_OPEN)로 나눴고, 활성 상태만 초록색으로 보이도록 색상을 정리했습니다.
- `docker-compose.yml`에 ngrok 프로필을 추가해서 `.env`의 토큰만 채워 두면 GitHub Webhook이 로컬 Jenkins로 바로 연결되도록 했습니다.

## 확인 방법
1. `docker compose --profile ngrok up -d` 로 전부 띄운 뒤 `http://localhost:8761`에서 서비스가 Eureka에 올라왔는지 확인합니다.
2. Grafana(`http://localhost:3000`, 계정 `admin/admin`)에서 Payment Service Overview 대시보드를 열면 Circuit Breaker State 패널이 여섯 개 타일로 표시되고, 현재 상태만 초록색인 것을 확인할 수 있습니다.
3. `scripts/test-circuit-breaker.sh`를 직접 돌리거나 Jenkins 빌드 로그를 보면 Kafka 중단 → 느린 호출 → 재기동 → 복구 순서가 자동으로 검증됩니다.

## 다음 주 준비
- 결제 정산/대사(Settlement & Reconciliation) 로직 설계를 시작해야 합니다.
- Spring Cloud Gateway 도입 여부를 검토하고 POC를 진행합니다.
- Istio / Linkerd 중 어떤 Service Mesh가 더 적합한지 비교할 계획입니다.