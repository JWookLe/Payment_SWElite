# 6주차 작업

## 0. 주간 목표

- **Kafka Outbox 파이프라인을 비동기화하여 TPS·복원력을 동시에 끌어올리기**
- 게이트웨이 HTTP 클라이언트/커넥션 풀 정비로 API 타임아웃을 선제적으로 제어
- KT Cloud VM1·VM2 운영 구조를 환경별 compose/state 파일로 분리하고, Prometheus 설정을 안전하게 관리

### 핵심 성과

| 지표                              | Before               | After                               | 개선율/특징          |
| --------------------------------- | -------------------- | ----------------------------------- | -------------------- |
| **Outbox 재처리 배치 크기**     | 100 events / 10초     | 1,000 events / 250ms                | **40배↑** (즉각 복원) |
| **Kafka 토픽 파티션**           | 1개                  | 승인/정산/환불 6개, DLQ 3개         | 소비 지연 최소화     |
| **Gateway 연결 타임아웃**       | Spring Cloud 기본값  | 2초 연결, 20초 응답, 5초 풀 획득    | 장애 전파 차단       |
| **VM 운영 관리**                 | 단일 compose 공유    | state/app 분리, Prometheus cloud별  | 로컬/클라우드 충돌 無 |

---

## 1. Kafka Outbox & 비동기 파이프라인

### 1-1. PaymentEventPublisher 비동기화

- `PaymentApplication`에 `@EnableAsync`를 추가하고, `config/AsyncConfig`에서 전용 `ThreadPoolTaskExecutor(outbox-dispatch-*)`를 구성했다.
- `PaymentEventPublisher`는 Outbox 저장 직후 HTTP 스레드를 즉시 반환하고, 트랜잭션 커밋 이후 `dispatchOutboxEventAsync`로 Kafka 전송을 큐잉한다. `TransactionTemplate`을 주입해 재시도 시에도 DB 일관성을 보장하고, Circuit Breaker 감시 로깅을 강화했다.
- 동작 검증을 위해 `PaymentEventPublisherTest`에 `SyncTaskExecutor` + `TransactionTemplate` mock을 주입해 비동기 경로를 커버했다.

### 1-2. Outbox Scheduler & 자원 튜닝

- `OutboxEventScheduler`는 `batch-size 100 → 1000`, `interval 10s → 250ms`, `max-retries 10 → 20`, `retry-interval 30s → 5s`로 상향하여 Kafka 장애 후에도 수 초 내 복구된다.
- 회로가 OPEN이어도 폴링을 건너뛰지 않고, 이벤트마다 `dispatchOutboxEventAsync`를 호출해 워커 풀에서 처리한다. 큐잉 수·DLQ 후보를 구분해 로깅한다.
- `application.yml`에는 Hikari 풀(300/60), Tomcat 스레드/커넥션(400/50/4000), Kafka producer 타임아웃(2s/3s), 배치 사이즈(32KB), idempotence 옵션을 적용해 고부하 대비치를 맞췄다.
- Kafka 토픽 파티션을 승인/정산/환불 6개, DLQ 3개로 확장하여 워커 스케일아웃 기반을 마련했다.

### 1-3. docker-compose 연동

- `docker-compose.yml`에서 인게스트 환경변수(Hikari·Tomcat·connect timeout 등)를 expose하고, MariaDB `--max-connections`를 600으로 늘렸다.
- Monitoring 서비스 계정은 root로 실행하도록 수정해 k6/스크립트 호출 시 권한 문제를 없앴다.

## 2. Gateway & API 안정성

### 2-1. HTTP 클라이언트 타임아웃 기본값 지정

- `backend/gateway/src/main/resources/application.yml`에 `spring.cloud.gateway.httpclient` 블록을 추가하여 `connect-timeout=2s`, `response-timeout=20s`, `pool.max-connections=4000`, `pool.acquire-timeout=5s`를 기본값으로 준수하도록 했다.
- 동일 파라미터를 `docker-compose.yml`에서도 환경변수로 노출해 KT Cloud 운영 시 동적으로 조절 가능하도록 했다.

### 2-2. 5173 포트 및 서비스 종속성 점검

- VM2에서 `docker compose ps frontend` 결과를 확인해 컨테이너가 꺼져 있음을 안내하고, `docker compose up -d --build frontend`로 재기동하는 절차를 문서화했다.
- 프런트엔드 접근은 보안그룹에 TCP 5173 인바운드를 추가하거나 Gateway 뒤에서 reverse proxy 하는 두 가지 옵션을 정리했다.

## 3. KT Cloud 운영 정비

### 3-1. VM1(state) / VM2(app) 역할 분리

- VM1(172.25.0.37)은 DB/Kafka/Redis/Eureka/ingest/monitoring 전용 `docker-compose.state.yml`, VM2(172.25.0.79)는 Gateway/worker/frontend 전용 `docker-compose.app.yml`로 구성하도록 절차를 정의했다.
- `git status` 오염을 막기 위해 각 VM에서 로컬 compose 파일과 Prometheus cloud 설정을 `.git/info/exclude`에 등록하고, pull 전에 `git stash`가 필요 없도록 했다.

### 3-2. Prometheus 클라우드 구성

- VM1에서만 필요한 IP 기반 타깃을 `monitoring/prometheus/prometheus_cloud.yml`로 분리하고, compose에서 해당 파일을 `/etc/prometheus/prometheus.yml`로 마운트하도록 했다.
- 공용 `prometheus.yml`은 로컬/CI용 기본값으로 유지하여 환경별 충돌을 예방했다.

### 3-3. 네트워크·CLI 지원

- Rocky Linux 9.6 환경에서 Codex CLI 설치 시 DNS/핑 진단 절차(`ping 8.8.8.8`, `/etc/resolv.conf`, 프록시 설정`)를 정리했다.
- SSH/Vite 포트 문제, Jenkins/프론트 접속, KT Console 계정 사용법 등을 구체적으로 가이드하여 원격 운영자가 즉시 참조할 수 있게 했다.

## 4. 다음 주 우선과제

1. Kafka Consumer/Worker 측도 `TaskExecutor` 기반으로 병렬 처리 전략을 정립하고, DLQ 처리 루틴을 코드로 반영.
2. Gateway Timeout/Retry 지표를 Prometheus로 수집하고, Grafana 경보 룰을 구성.
3. `docker-compose.state.yml`/`app.yml`을 공식 레포에 parameterized 템플릿 형태로 포함하여, Jenkins 파이프라인에서도 동일 구성을 재사용.

