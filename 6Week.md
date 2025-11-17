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

## 1. Compose 분리 & Git 관리

### 1-1. VM1(state) / VM2(app) 분리

- VM1: `docker-compose.state.yml`에 DB/Kafka/Redis/Eureka/ingest/monitoring/prometheus/grafana 서비스를 묶어 실행.
- VM2: `docker-compose.app.yml`에 gateway/worker/frontend 등 애플리케이션 계층만 포함.
- 각 VM에서 `git status -sb` 시 전용 파일이 계속 잡히지 않도록 `.git/info/exclude`에 해당 파일명을 추가.

### 1-2. Prometheus 설정 전략

- Prometheus가 한 인스턴스에서 VM1 내부 컨테이너(gateway, ingest 등)와 VM2 IP(172.25.0.79:8080 등)를 동시에 모니터링해야 하므로, “컨테이너 이름 + IP 혼용”을 허용하기로 결정.
- VM1 전용 설정은 `monitoring/prometheus/prometheus_cloud.yml`로 보관하고, `docker-compose.state.yml`에서 `./monitoring/prometheus/prometheus_cloud.yml:/etc/prometheus/prometheus.yml`로 마운트.
- 해당 파일은 VM1 로컬에서만 필요하므로 `.git/info/exclude`에 등록하여 pull 시 충돌을 방지.
- 공용 `prometheus.yml`은 필요 시 기본값으로 복구하거나 삭제 상태를 유지하고, 클라우드 버전만 운영.

### 1-3. Git 상태 정리

- `git status`에 로컬 전용 파일이 계속 뜨는 문제 해결을 위해 `git status -sb` 확인 → `echo '<파일경로>' >> .git/info/exclude`.
- Prometheus 기본 파일이 삭제 상태로 남지 않도록 `git checkout -- monitoring/prometheus/prometheus.yml`로 복원하거나, 삭제를 확정할 경우 커밋.

---

## 2. CORS 및 Gateway 라우팅 문제 해결

### 2-1. 403 Forbidden 오류 해결

오늘 아침 Admin Dashboard 접속 시 403 Forbidden 오류가 발생했다. 원인을 분석해보니 Gateway의 CORS 설정이 프론트엔드 요청을 차단하고 있었다.

**문제 상황:**
- Frontend(VM2 5173포트)에서 Gateway(VM2 8080포트)로 API 요청 시 403 에러
- 브라우저 콘솔에 CORS 관련 오류 메시지 확인

**해결 과정:**
1. Gateway의 `application.yml`에서 CORS 설정 확인
2. `GATEWAY_ALLOWED_ORIGINS` 환경변수가 localhost만 허용하고 있었음
3. VM2의 docker-compose.app.yml에 실제 프론트엔드 주소 추가:
   ```yaml
   GATEWAY_ALLOWED_ORIGINS: "http://localhost:5173,http://172.25.0.79:5173"
   ```
4. Gateway 재시작 후 정상 동작 확인

### 2-2. Gateway 라우팅 설정 점검

Admin API 요청이 제대로 라우팅되지 않는 문제도 발견했다.

**수정 내용:**
- `/api/admin/**` 경로가 Monitoring Service로 정확히 라우팅되도록 설정
- RewritePath 필터 적용하여 `/api/admin/` prefix 유지

---

## 3. Docker 멀티스테이지 빌드 적용

모든 백엔드 서비스에 멀티스테이지 빌드를 적용하여 로컬 Gradle 빌드 없이 Docker 이미지 내에서 컴파일하도록 변경했다.

### 3-1. 대상 서비스

- consumer-worker
- settlement-worker
- refund-worker
- eureka-server
- monitoring-service

### 3-2. 주요 변경사항

각 서비스별로 다음 파일들을 수정/생성:

**build.gradle.kts:**
```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

repositories {
    mavenCentral()
}
```
- repositories 블록 추가하여 Gradle이 의존성을 다운로드할 수 있도록 함

**Dockerfile (멀티스테이지):**
```dockerfile
# Build stage
FROM gradle:8-jdk21 AS builder
WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre
# ... 런타임 설정
COPY --from=builder --chown=spring:spring /build/build/libs/*.jar app.jar
```
- Builder 스테이지에서 소스 코드 컴파일
- Runtime 스테이지에서 JAR만 복사하여 이미지 크기 최소화

**.dockerignore:**
- 불필요한 빌드 아티팩트 제외하여 빌드 컨텍스트 최적화

**settings.gradle.kts:**
- pluginManagement 블록 추가로 플러그인 버전 명시

### 3-3. Monitoring Service 특별 설정

Monitoring Service는 테스트 스크립트 실행을 위해 추가 패키지가 필요:
- curl: API 호출용
- bash: 스크립트 실행
- docker-ce-cli: Docker 명령어 실행 (Kafka 중지/시작)
- nodejs: MCP 서버 실행
- k6: 부하 테스트 도구

스크립트 및 테스트 파일 복사:
```dockerfile
COPY --chown=spring:spring scripts/ ./scripts/
COPY --chown=spring:spring loadtest/ ./loadtest/
RUN chmod +x ./scripts/*.sh || true
```

---

## 4. 데이터베이스 스키마 설정

Admin Dashboard의 Database Stats 테스트가 실패하여 원인을 파악했다.

### 4-1. 문제 상황

```
bad SQL grammar [SELECT COUNT(*) as count FROM ledger_entry]
Table 'paydb.ledger_entry' doesn't exist
```

테이블이 생성되지 않아 쿼리가 실패하고 있었다.

### 4-2. 해결 과정

1. MariaDB 접속 정보 확인:
   - Host: mariadb (Docker 네트워크 내)
   - Database: paydb
   - User: root / Password: 4535

2. 스키마 파일 실행:
   ```bash
   docker exec -i pay-mariadb mysql -uroot -p4535 paydb < backend/ingest-service/src/main/resources/schema.sql
   ```

3. 생성된 테이블:
   - payment (결제 정보)
   - ledger_entry (원장 기록)
   - outbox_event (이벤트 발행)
   - idem_response_cache (멱등성 캐시)
   - settlement_request (정산 요청)
   - refund_request (환불 요청)

4. payuser 권한 부여:
   ```sql
   GRANT ALL PRIVILEGES ON paydb.* TO 'payuser'@'%';
   FLUSH PRIVILEGES;
   ```

---

## 5. Circuit Breaker 테스트 구현

Admin Dashboard에서 Circuit Breaker 테스트를 실행할 수 있도록 기능을 구현했다.

### 5-1. 초기 문제

테스트 버튼을 누르면 즉시 실패로 표시되고, Grafana에서 request rate가 0으로 나타났다. 실제로 테스트가 실행되지 않고 있었다.

### 5-2. 원인 분석

1. **Gateway 컨테이너 참조 문제:**
   - 기존 스크립트가 `gateway_exec`를 사용하여 Gateway 컨테이너 내에서 curl 실행
   - VM1의 Monitoring Service 컨테이너에서는 Gateway가 없음 (VM2에 있음)
   - "No such container: gateway" 오류 발생

2. **Volume 마운트 확인:**
   - `/root/Payment_SWElite/scripts` → `/app/scripts` (read-only)
   - `/root/Payment_SWElite/loadtest` → `/app/loadtest`
   - Docker 이미지 내 스크립트가 아닌 호스트의 스크립트를 사용

### 5-3. 해결책

루트의 `scripts/test-circuit-breaker.sh` 파일 수정:

1. **API 호출 방식 변경:**
   ```bash
   # Before
   gateway_exec timeout "${timeout_seconds}" curl ...

   # After - 직접 curl 사용
   timeout "${timeout_seconds}" curl -sSf -X POST "${GATEWAY_BASE_URL}/payments/authorize" ...
   ```

2. **Health Check 방식 변경:**
   ```bash
   # Before
   ingest_curl -s -f "${API_BASE_URL}/actuator/health"

   # After - 직접 curl 사용
   curl -s -f "${API_BASE_URL}/actuator/health"
   ```

3. **환경변수 설정 (AdminTestService.java):**
   ```java
   processBuilder.environment().put("API_BASE_URL", "http://pay-ingest:8080");
   processBuilder.environment().put("GATEWAY_BASE_URL", gatewayBaseUrl + "/api");
   ```
   - API_BASE_URL: 같은 VM1 내의 ingest-service
   - GATEWAY_BASE_URL: VM2의 Gateway IP (172.25.0.79:8080)

### 5-4. Docker Socket 마운트

Kafka 중지/시작을 위해 Docker 명령어를 실행해야 하므로 docker-compose.state.yml에서 Docker socket 마운트:
```yaml
monitoring-service:
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
```

---

## 6. K6 부하 테스트 지원 추가

K6 부하 테스트도 Admin Dashboard에서 실행할 수 있도록 구현했다.

### 6-1. 파일 구성

- `scripts/run-k6-test.sh`: K6 테스트 실행 스크립트
- `loadtest/k6/payment-scenario.js`: 부하 테스트 시나리오
- 위 파일들이 Volume 마운트로 컨테이너에서 접근 가능

### 6-2. 테스트 시나리오

세 가지 시나리오 지원:
- authorize-only: 결제 승인만
- authorize-capture: 결제 승인 + 매입
- full-flow: 결제 승인 + 매입 + 환불

### 6-3. BASE_URL 설정

AdminTestService에서 K6 스크립트 실행 시 Gateway URL을 환경변수로 전달:
```java
processBuilder.environment().put("BASE_URL", gatewayBaseUrl + "/api");
```

### 6-4. 부하 테스트 설정

K6 시나리오는 ramping-arrival-rate 실행기를 사용:
- 30초: 100 RPS까지 웜업
- 1분: 200 RPS까지 증가
- 2분: 300 RPS까지 증가
- 2분: 400 RPS 도달
- 2분: 400 RPS 유지
- 30초: 쿨다운

임계값:
- HTTP 실패율 5% 미만
- 95 percentile 응답 시간 1초 미만
- 결제 오류율 2% 미만

현재 DB 자원 제한으로 인해 테스트 성공률이 100%는 아니지만, 테스트 자체는 정상 실행된다.

---

## 7. 최종 결과

### 7-1. 완성된 기능

1. **Admin Dashboard 모든 테스트 동작:**
   - Database Stats: 테이블별 레코드 수 조회
   - Redis Connection: Redis 연결 상태 확인
   - Kafka Health: Kafka 브로커 상태 확인
   - Settlement Stats: 정산 통계 조회
   - Circuit Breaker Test: Kafka 장애 시뮬레이션 및 복구 검증
   - K6 Load Test: 부하 테스트 실행

2. **멀티스테이지 빌드:**
   - 모든 백엔드 서비스가 Docker 내에서 컴파일
   - 로컬 Gradle 설치 없이도 빌드 가능

3. **CORS 및 라우팅:**
   - 프론트엔드-Gateway 간 CORS 정상 동작
   - 모든 API 경로 정확히 라우팅

### 7-2. VM 구성 현황

**VM1 (172.25.0.37):**
- MariaDB (13306)
- Kafka (9092), Zookeeper
- Redis
- Eureka Server (8761)
- Ingest Service (8081 → 결제 API)
- Monitoring Service (8082 → 관리자 API + 테스트 실행)
- Prometheus, Grafana (모니터링)

**VM2 (172.25.0.79):**
- API Gateway (8080)
- Frontend (5173)
- Consumer/Settlement/Refund Workers

### 7-3. 향후 개선사항

- K6 테스트 시 DB 자원 최적화로 성공률 향상 필요
- MCP 서버 연동으로 AI 기반 분석 보고서 생성
- 자동화된 CI/CD 파이프라인 구축
