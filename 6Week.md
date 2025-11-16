# 6주차 작업

## 0. 주간 목표

- KT Cloud VM1·VM2 환경에서 **운영자가 바로 진단·배포할 수 있는 접속/네트워크 절차** 정리
- 프런트엔드(5173)·Gateway 노출 상태 및 Docker compose 분리를 점검하여 **VM별 역할을 명확히 유지**
- Prometheus가 **컨테이너 이름 + 내부 IP를 혼용**해도 충돌 없이 관리되도록 Git/구성 전략 수립

### 핵심 메모

| 항목                               | 현황/결론                                                                        |
| ---------------------------------- | -------------------------------------------------------------------------------- |
| OS/패키지 관리                    | Rocky Linux 9.6 → `dnf` 사용, `apt` 명령 없음                                    |
| 외부 네트워크 진단                | `ping 8.8.8.8` 100% loss → 보안그룹/프록시 확인 필요                              |
| 프런트 5173 접근                   | 컨테이너 미기동 + 보안그룹 미개방 → `docker compose up frontend` 후 5173 허용 필요 |
| Compose 파일                      | VM1(state)·VM2(app) 전용 파일 사용, `.git/info/exclude`에 등록하여 pull 방해 제거 |
| Prometheus 설정                    | 컨테이너 이름 + 172.25.x.x IP 혼용 허용, 클라우드 버전(`prometheus_cloud.yml`)은 Git 무시 |

---

## 1. SSH & 네트워크 진단 가이드

### 1-1. SSH 계정/명령 정리

- 사용자에게 전달된 KT 콘솔 계정(hecto-prt-2-1/2)과 VM root 패스워드를 기반으로 `ssh root@210.104.76.135/136` 접속 절차를 재정리했다.
- Jenkins/Frontend 등 포트 접근 시 KT 콘솔 보안그룹을 수정해야 하므로, “포트 접근 문제 → SG/방화벽/보안장비 순으로 점검” 흐름을 문서화.

### 1-2. 패키지 관리자 차이

- `/etc/os-release` 확인 결과 Rocky Linux 9.6 → `apt`가 없으므로 `dnf`/`yum`을 사용하도록 안내.
- Codex CLI 설치 명령: `dnf update -y && dnf install -y curl unzip tar` → `curl -fsSL https://cli.openai.com/install.sh | bash`.

### 1-3. DNS/Outbound 이슈

- `ping 8.8.8.8 -c 4`가 100% packet loss → outbound 자체가 막혀 있음을 확인.
- 조치 순서: 보안그룹/NAT/프록시 정책 확인 → 필요 시 공용 DNS(8.8.8.8) 또는 프록시 설정 적용 → 외부 접근 불가 시 로컬에서 설치 파일을 받아 `scp`.

## 2. 프런트엔드/포트 점검

- VM2에서 `docker compose ps frontend` 결과가 비어 있어 컨테이너가 내려가 있음을 확인했고, `docker compose up -d frontend` 또는 `--build` 옵션으로 재기동하는 절차를 공유했다.
- 5173 포트는 KT 보안그룹에서 기본적으로 막혀 있으므로, 외부 노출이 필요하면 `TCP 5173` 인바운드 허용 또는 Gateway를 통해 reverse proxy 구성.
- VM을 꺼도 컨테이너는 계속 뜨므로, 접속 불가 시 `docker compose ps`/`docker logs`로 상태를 확인하도록 안내.

## 3. Compose 분리 & Git 관리

### 3-1. VM1(state) / VM2(app) 분리

- VM1: `docker-compose.state.yml`에 DB/Kafka/Redis/Eureka/ingest/monitoring/prometheus/grafana 서비스를 묶어 실행.
- VM2: `docker-compose.app.yml`에 gateway/worker/frontend 등 애플리케이션 계층만 포함.
- 각 VM에서 `git status -sb` 시 전용 파일이 계속 잡히지 않도록 `.git/info/exclude`에 해당 파일명을 추가.

### 3-2. Prometheus 설정 전략

- Prometheus가 한 인스턴스에서 VM1 내부 컨테이너(gateway, ingest 등)와 VM2 IP(172.25.0.79:8080 등)를 동시에 모니터링해야 하므로, “컨테이너 이름 + IP 혼용”을 허용하기로 결정.
- VM1 전용 설정은 `monitoring/prometheus/prometheus_cloud.yml`로 보관하고, `docker-compose.state.yml`에서 `./monitoring/prometheus/prometheus_cloud.yml:/etc/prometheus/prometheus.yml`로 마운트.
- 해당 파일은 VM1 로컬에서만 필요하므로 `.git/info/exclude`에 등록하여 pull 시 충돌을 방지.
- 공용 `prometheus.yml`은 필요 시 기본값으로 복구하거나 삭제 상태를 유지하고, 클라우드 버전만 운영.

### 3-3. Git 상태 정리

- `git status`에 로컬 전용 파일이 계속 뜨는 문제 해결을 위해 `git status -sb` 확인 → `echo '<파일경로>' >> .git/info/exclude`.
- Prometheus 기본 파일이 삭제 상태로 남지 않도록 `git checkout -- monitoring/prometheus/prometheus.yml`로 복원하거나, 삭제를 확정할 경우 커밋.

## 4. 문서화 & 다음 액션

1. `6Week.md`에 SSH/네트워크 진단, 프런트 포트 점검, compose 분리, Prometheus 관리 전략을 기록.
2. VM1/VM2에서 `git pull origin main`이 바로 되도록 전용 파일을 ignore 했으며, 이후에도 동일 패턴을 유지.
3. 다음 주에는 compose.state/app 구조를 공식 레포에 템플릿 형태로 추가하고, Prometheus cloud 파일을 자동 생성하는 스크립트를 준비 예정.

