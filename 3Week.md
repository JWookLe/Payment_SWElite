# 3주차 진행 요약

## 개요
- Kafka 퍼블리셔를 보호하기 위한 Resilience4j 기반 Circuit Breaker를 도입하고 Jenkins 파이프라인에도 자동 검증 절차를 편입했습니다.
- 관측성을 높이기 위해 Grafana 대시보드를 보강하고, 상태 패널이 즉시 식별 가능하도록 JSON 정의를 수정했습니다.
- 관련 개발/운영 문서를 정리해 팀원 누구나 회로 차단기 동작과 테스트 방법을 빠르게 파악할 수 있도록 했습니다.

## 구현 상세
- **Circuit Breaker 적용**: ackend/ingest-service 모듈에서 PaymentEventPublisher에 Resilience4j Circuit Breaker를 삽입하고, 실패/지연 조건을 감지하도록 구성했습니다. (CIRCUIT_BREAKER_GUIDE.md 참고)
- **환경 설정**: pplication.yml 및 Resilience4jConfig에 임계치, 대기 시간, HALF_OPEN 허용 호출 수 등을 정의해 장애 발생 시 빠른 차단과 자동 복구가 가능하게 했습니다.
- **에러 처리 플로우**: Circuit Breaker가 OPEN 상태일 때 Outbox 이벤트는 DB에 남겨 재시도하며, NotPermitted 예외를 로깅하여 운영자가 즉시 상태를 확인할 수 있도록 했습니다.

## 자동화 & 테스트
- **자동 시나리오 스크립트**: scripts/test-circuit-breaker.sh에서 Kafka 중단 → 느린 요청 → 재기동 → 복구 요청까지 9단계 시나리오를 자동 실행하며, 내부 curl 호출도 Docker Compose 네트워크에서 수행하도록 개선했습니다.
- **Jenkins 파이프라인 연동**: Jenkinsfile에 “Circuit Breaker Test” 스테이지를 추가해, 빌드 시마다 스크립트가 실행되고 결과에 따라 빌드 성공/실패가 결정되도록 했습니다.
- **ngrok 연동 자동화**: docker-compose.yml에 
grok 서비스를 프로필로 추가하고 .env에서 토큰을 읽어, GitHub Webhook → Jenkins 파이프라인 트리거가 자동으로 이어지도록 구성했습니다.

## 관측성 보강
- **Grafana 패널**: monitoring/grafana/dashboards/payment-overview.json을 수정해 Circuit Breaker State 패널이 CLOSED/OPEN/HALF_OPEN/DISABLED/FORCED_OPEN/FORCED_HALF_OPEN 여섯 상태를 각각의 타일로 보여 주고, 활성 상태만 초록색으로 표시되도록 했습니다.
- **지표 점검**: Failure Rate, Slow Call Rate 등 관련 패널이 Resilience4j 메트릭(Prometheus)을 정확하게 노출하는지 확인했습니다.

## 문서화
- CIRCUIT_BREAKER_GUIDE.md를 최신 구현 내용에 맞춰 보강하고, 회로 차단기 상태 및 테스트 절차를 상세 기록했습니다.
- README에 Grafana 패널 사용법과 ngrok 기반 Webhook 자동화를 정리했습니다.
- 3주차 작업을 별도 문서로 분리해 히스토리를 추적하기 쉽게 만들었습니다.

## 남은 과제
- Settlement/Reconciliation 백엔드 로직 설계 및 구현
- API Gateway(SCG) 도입 여부 결정 및 PoC
- Istio 또는 Linkerd 기반 Service Mesh 도입 검토
