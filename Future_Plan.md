# Future Plan: Schema & Config Consistency

목표: 대규모 트래픽 환경에서 스키마 드리프트를 막고, 모든 환경(dev/stage/prod)에서 동일한 DB/설정 상태를 보장하는 배포 파이프라인을 마련한다.

## 1) 마이그레이션 도구 도입 (Flyway/Liquibase)
- Flyway/Liquibase 의존성 추가 후 `schema.sql`을 버전별 마이그레이션 파일로 분할 (예: `V20250201__create_payment.sql`).
- 기준: 신규 테이블/컬럼/인덱스/뷰/시드 데이터는 모두 마이그레이션 파일로 관리, 수동 DDL 금지.
- 샤딩/멀티 DB 대응: shard1/shard2 각각 마이그레이션를 돌릴 수 있도록 데이터소스 프로파일 분리.
- 검증: 로컬/CI에서 `flyway validate` 또는 `liquibase validate`로 불일치 감지 후 빌드 실패 처리.

## 2) 런타임 DDL 차단
- `spring.jpa.hibernate.ddl-auto=validate`로 전환하여 애플리케이션이 스키마를 생성/수정하지 못하게 한다.
- 개발/테스트 환경에서도 동일하게 적용해 드리프트를 사전에 차단.

## 3) CI/CD 파이프라인 연동
- Jenkins에 마이그레이션 단계 추가:
  - Build 전 `flyway clean`(옵션) → `flyway migrate`(필수) 실행.
  - 실패 시 배포 중단, 성공 시만 이미지/배포 진행.
- 멀티 환경 변수 세트(dev/stage/prod)로 DB 연결 정보를 분리해 동일 파이프라인을 재사용.
- 배포 롤백 절차: 마지막 성공 마이그레이션 버전으로 롤백 스크립트 준비(필요 시 다운 마이그레이션).

## 4) 운영 적용 체크리스트
- [ ] Flyway/Liquibase 의존성 추가 및 초기 마이그레이션 커밋
- [ ] `ddl-auto=validate` 적용 및 부트 시 검증 통과 확인
- [ ] shard1/shard2 대상 마이그레이션 실행 스크립트 마련
- [ ] Jenkins 마이그레이션 스테이지 추가 및 실패 시 배포 중단
- [ ] 다운 마이그레이션/백업-복구 절차 문서화
- [ ] dev/stage/prod 환경 변수 세트 분리 및 보관(Vault/Config Server 도입 시 연동)

# Future Plan: Data High Availability & Resilience

목표: Redis/MariaDB 장애에도 서비스가 중단되지 않고, 데이터 유실 위험을 최소화한다.

## 1) Redis 고가용성
- Sentinel 또는 Cluster 구성으로 마스터 장애 시 자동 승격 경로 확보.
- 애플리케이션 설정에 Sentinel/Cluster 엔드포인트 반영 후 장애 전환 테스트.
- Rate limiting/Idempotency에서 Redis 장애 시 fail-open 여부와 영향 범위 검증(필요 시 캐싱 우회/알림).

## 2) MariaDB 보호·확장
- 자동 백업 스케줄 즉시 적용(예: 일별 풀 백업 + 시점 복구를 위한 binlog 보관).
- Read Replica 추가 및 Read/Write 분리 설계: 읽기 트래픽을 Replica로 분산, 쓰기는 Primary 고정.
- Failover 절차 마련: 자동/수동 전환 시나리오, 연결 문자열/서비스 재기동 전략 정의.

## 3) 운영 적용 체크리스트
- [ ] Redis Sentinel/Cluster 구성 및 앱 설정 반영
- [ ] Redis 장애 전환/복구 시나리오 테스트
- [ ] MariaDB 백업 스케줄 설정 및 복구 리허설
- [ ] Read Replica 구축 및 Read/Write 분리 전략 문서화
- [ ] Failover 절차서(자동/수동) 및 알림 흐름 정리

# Future Plan: PG/Kafka Call Resilience (Retry + Idempotence)

현재: 서킷 브레이커(CB) 테스트 스크립트로 Kafka 다운/업 시 OPEN→HALF_OPEN→CLOSED 전환을 검증하는 수준. 실제 운영 환경에서는 일시 장애에 대한 “짧은 재시도+백오프+아이도포턴스”를 함께 넣어야 성공률을 높이고 유실/중복을 막을 수 있음.

## 1) PG 호출 재시도
- Resilience4j Retry 또는 Spring Retry로 PG 승인 호출에 짧은 재시도(예: maxAttempts 2~3) + 지터 백오프 적용.
- 재시도/실패 메트릭(Micrometer) 노출 → Grafana 패널에 성공/실패/재시도 비율 추가.
- Circuit Breaker와 병행: CB는 실패율 스위치, Retry는 단기 회복용으로 역할을 분리.

## 2) Kafka 프로듀서 신뢰성
- `enable.idempotence=true`, `retries`, `linger.ms`, `batch.size` 등을 고부하 기준으로 재조정.
- 프로듀서 레벨 재시도/에러 메트릭 노출, Outbox 폴링 주기/배치와 함께 튜닝.
- 목적: 일시 브로커 글리치 시 성공률↑, 중복 전송 시 단일 커밋 보장.

## 3) 운영 적용 체크리스트
- [ ] Resilience4j Retry/Spring Retry 의존성 추가 및 PG 승인 호출에 적용
- [ ] PG 재시도 파라미터(시도 횟수/백오프/타임아웃) 확정 및 메트릭 대시보드 추가
- [ ] Kafka 프로듀서 `enable.idempotence=true` 및 재시도/배치 파라미터 튜닝
- [ ] 재시도·CB 메트릭을 Grafana에 노출(성공/실패/재시도/CB 상태)
