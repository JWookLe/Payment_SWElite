# Payment_SWElite
## 二쇱감蹂?紐⑺몴

### 1二쇱감
- React 紐⑹뾽 ?ㅽ넗?댁? Spring Boot 湲곕컲 寃곗젣 API(?뱀씤/?뺤궛/?섎텋)濡?E2E ?먮쫫 援ы쁽
- Kafka, Redis, MariaDB, Jenkins媛 ?ы븿??Docker Compose 濡쒖뺄 ?섍꼍 援ъ텞

### 2二쇱감
- [x] Redis 湲곕컲 rate limit 諛?硫깅벑 罹먯떆 怨좊룄??
- [x] Prometheus + Grafana 吏???섏쭛 諛??쒓컖???뚯씠?꾨씪??援ъ꽦
- [x] k6 遺???ㅽ듃?덉뒪 ?뚯뒪???쒕굹由ъ삤 ?묒꽦 諛?200 RPS 紐⑺몴 ?ъ꽦
- [x] GitHub Webhook + Jenkins ?먮룞 鍮뚮뱶 ?뚯씠?꾨씪??援ъ꽦
- [ ] Settlement/Reconciliation ?鍮?鍮꾨룞湲?泥섎━ 蹂닿컯
- [x] payment.dlq ?좏뵿 ?ъ쟾??湲곕컲 Consumer ?덉쇅 泥섎━ 蹂닿컯

### 3二쇱감 (?꾩옱)
- [x] Resilience4j 湲곕컲 Circuit Breaker 援ы쁽 (Kafka Publisher 蹂댄샇)
- [x] Circuit Breaker ?먮룞 ?뚯뒪??諛?紐⑤땲?곕쭅 (9?④퀎 ?쒕굹由ъ삤)
- [x] Grafana??Circuit Breaker ?⑤꼸 異붽? (6媛??⑤꼸)
- [x] Jenkins ?뚯씠?꾨씪?몄뿉 Circuit Breaker Test ?④퀎 ?듯빀
- [x] Circuit Breaker ?꾨꼍 媛?대뱶 臾몄꽌??(?쒓뎅??
- [x] Spring Cloud Eureka 湲곕컲 Service Discovery 援ы쁽
- [ ] API Gateway ?꾩엯 (Spring Cloud Gateway)
- [ ] Service Mesh 寃??(Istio ?먮뒗 Linkerd)

## ?쒕퉬??援ъ꽦 ?붿냼
| 援ъ꽦 | ?ㅻ챸 |
| --- | --- |
| **eureka-server** | Spring Cloud Eureka 湲곕컲 Service Discovery ?쒕쾭. ingest-service? consumer-worker???쒕퉬???깅줉/議고쉶 ?대떦 (?ы듃 8761). |
| **frontend** | React + Vite濡??묒꽦??紐⑹뾽 ?ㅽ넗??UI. iPhone / Galaxy ??二쇱슂 ?⑤쭚 寃곗젣 ?쒕굹由ъ삤 ?쒓났. |
| **ingest-service** | Spring Boot(Java 21) 湲곕컲 寃곗젣 API. ?뱀씤/?뺤궛/?섎텋 泥섎━? outbox ?대깽??諛쒗뻾 ?대떦. Eureka???먮룞 ?깅줉. |
| **consumer-worker** | Kafka Consumer. 寃곗젣 ?대깽?몃? ledger ?뷀듃由щ줈 諛섏쁺?섍퀬 DLQ 泥섎━ 濡쒖쭅 ?ы븿. Eureka???먮룞 ?깅줉. |
| **mariadb** | paydb ?ㅽ궎留??댁쁺. payment, ledger_entry, outbox_event, idem_response_cache ?뚯씠釉?愿由? |
| **kafka & zookeeper** | 寃곗젣 ?대깽???좏뵿(`payment.authorized`, `payment.captured`, `payment.refunded`)???몄뒪?? |
| **redis** | rate limit 移댁슫??諛?寃곗젣 ?뱀씤 ?묐떟 硫깅벑 罹먯떆 ??? |
| **jenkins** | CI ?쒕쾭. Gradle/NPM 鍮뚮뱶, Docker Compose 諛고룷, k6 遺???뚯뒪???먮룞?? |
| **prometheus/grafana** | ?좏뵆由ъ??댁뀡 硫뷀듃由??섏쭛 諛???쒕낫???쒓났. Eureka ?쒕쾭 硫뷀듃由?룄 ?ы븿. |

### Gateway Overview (English)
- Spring Cloud Gateway exposes `/api/**` and routes to Eureka-discovered services (e.g., `INGEST-SERVICE`).
- Default route: `/api/payments/**` → `lb://INGEST-SERVICE`, with `StripPrefix=1` filter.
- Query gateway health: `curl http://localhost:8080/actuator/health`.


## 二쇱슂 ?곗씠?곕쿋?댁뒪 DDL
`backend/ingest-service/src/main/resources/schema.sql` 李멸퀬
- `payment`: 寃곗젣 ?곹깭 諛?硫깅벑 ??蹂닿?
- `ledger_entry`: ?뱀씤/?뺤궛/?섎텋 ???앹꽦?섎뒗 ?뚭퀎 遺꾧컻 湲곕줉
- `outbox_event`: Kafka 諛쒗뻾 ???대깽????μ냼
- `idem_response_cache`: 寃곗젣 ?뱀씤 ?묐떟 硫깅벑 罹먯떆

## REST API ?붿빟
| Method | Path | ?ㅻ챸 |
| --- | --- | --- |
| `POST` | `/payments/authorize` | 硫깅벑 ??湲곕컲 寃곗젣 ?뱀씤 泥섎━ 諛?outbox 湲곕줉 |
| `POST` | `/payments/capture/{paymentId}` | ?뱀씤??寃곗젣 ?뺤궛 泥섎━, ledger 湲곕줉, ?대깽??諛쒗뻾 |
| `POST` | `/payments/refund/{paymentId}` | ?뺤궛 ?꾨즺 寃곗젣 ?섎텋 泥섎━, ledger 湲곕줉, ?대깽??諛쒗뻾 |

## Kafka ?좏뵿
- `payment.authorized`
- `payment.captured`
- `payment.refunded`
- `payment.dlq`

## Redis 湲곕컲 蹂댄샇 湲곕뒫
- ?뱀씤 API ?묐떟??Redis TTL 罹먯떆????ν빐??硫깅벑?깆쓣 蹂댁옣?? 湲곕낯 TTL? 600珥?(`APP_IDEMPOTENCY_CACHE_TTL_SECONDS`濡?議곗젙 媛??.
- 媛留뱀젏(`merchantId`)蹂??뱀씤쨌?뺤궛쨌?섎텋 API??Rate Limit???곸슜?? `APP_RATE_LIMIT_*` ?섍꼍 蹂?섎줈 議곗젙 媛?ν븯怨? Redis ?μ븷 ??fail-open ?꾨왂???ъ슜??

### ?깅뒫 紐⑺몴蹂?Rate Limit ?ㅼ젙
| ?섍꼍 | 紐⑺몴 RPS | Rate Limit (遺? | 鍮꾧퀬 |
|------|----------|----------------|------|
| **媛쒕컻** | ~10 RPS | 1,000/1,000/500 | 鍮좊Ⅸ ?쇰뱶諛?|
| **遺???뚯뒪??* | 200 RPS | 15,000/15,000/7,500 | ?꾩옱 ?ㅼ젙 |
| **?댁쁺 (紐⑺몴)** | 1,000 TPS | 70,000/70,000/35,000 | 理쒖쥌 紐⑺몴 |

## Observability (Prometheus & Grafana)

### ?ㅼ젙 諛??묒냽
- `docker compose up -d` ??Prometheus(9090)? Grafana(3000)媛 ?④퍡 湲곕룞??
- **Prometheus**: http://localhost:9090
  - Status ??Targets?먯꽌 ingest-service, consumer-worker 硫뷀듃由??섏쭛 ?곹깭 ?뺤씤
- **Grafana**: http://localhost:3000
  - 湲곕낯 怨꾩젙: `admin`/`admin`
  - `Payment Service Overview` ??쒕낫?쒖뿉???붿껌 ?띾룄, p95 吏?곗떆媛? Kafka ?뚮퉬?? ?먮윭???깆쓣 ?뺤씤

### 援ъ꽦 諛⑹떇
- **Prometheus**: 而ㅼ뒪? ?대?吏 鍮뚮뱶 (`monitoring/prometheus/Dockerfile`)
  - ?ㅼ젙 ?뚯씪???대?吏???ы븿?댁꽌 蹂쇰ⅷ 留덉슫??臾몄젣 ?닿껐
- **Grafana**: 而ㅼ뒪? ?대?吏 鍮뚮뱶 (`monitoring/grafana/Dockerfile`)
  - Datasource, Dashboard provisioning ?ㅼ젙???대?吏???ы븿
  - Payment Service Overview ??쒕낫???먮룞 濡쒕뱶

## Load Testing (k6)

### ?뚯뒪???쒕굹由ъ삤
`loadtest/k6/payment-scenario.js`???뱀씤 ???뺤궛 ??(?좏깮?? ?섎텋 ?먮쫫??寃利앺븿. ?섍꼍 蹂?섎줈 媛??④퀎瑜??좉??????덉쓬.

**?꾩옱 ?ㅼ젙** (200 RPS 紐⑺몴):
- Warm-up: 50 RPS (30珥?
- Ramp-up: 50 ??100 ??150 ??200 RPS (5遺?
- Sustain: 200 RPS (2遺?
- Cool-down: 0 RPS (30珥?
- **珥??뚯뒪???쒓컙**: 8遺?

### ?ㅽ뻾 諛⑸쾿

#### 湲곕낯 ?뚯뒪??(?뱀씤留?
```bash
MSYS_NO_PATHCONV=1 docker run --rm --network payment-swelite-pipeline_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
```

#### ?꾩껜 ?뚮줈???뚯뒪??(?뱀씤 ???뺤궛 ???섎텋)
```bash
MSYS_NO_PATHCONV=1 docker run --rm --network payment-swelite-pipeline_default \
  -v "$PWD/loadtest/k6":/k6 \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  -e ENABLE_CAPTURE=true \
  -e ENABLE_REFUND=true \
  grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
```

### ?깅뒫 紐⑺몴
-  **?먮윭??*: < 1%
-  **p95 ?묐떟?쒓컙**: < 100ms
-  **泥섎━??*: 200 RPS ?덉젙??泥섎━

## 濡쒖뺄 ?ㅽ뻾 諛⑸쾿
1. `docker compose up --build`
   - MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontend, Prometheus, Grafana瑜?湲곕룞??
2. ?꾨윴?몄뿏???묒냽: http://localhost:5173
3. API ?뺤씤 ?덉떆:
   ```bash
   curl -X POST http://localhost:8080/payments/authorize \
     -H 'Content-Type: application/json' \
     -d '{
       "merchantId":"M123",
       "amount":10000,
       "currency":"KRW",
       "idempotencyKey":"abc-123"
     }'
   ```
4. 醫낅즺: `docker compose down`

## Service Discovery (Spring Cloud Eureka)

Eureka??留덉씠?щ줈?쒕퉬???꾪궎?띿쿂?먯꽌 ?쒕퉬???깅줉/議고쉶??以묒븰 吏묒쨷??愿由щ? ?쒓났??

### 媛쒖슂
- **?쒕쾭**: Eureka Server (?ы듃 8761)
  - Self-preservation 鍮꾪솢?깊솕 (媛쒕컻 ?섍꼍)
  - ?ъ뒪 泥댄겕 諛?硫뷀듃由??몄텧 (Prometheus ?명솚)
- **?대씪?댁뼵??*: ingest-service, consumer-worker
  - ?먮룞 ?쒕퉬???깅줉 (IP 二쇱냼 湲곕컲)
  - ?덉??ㅽ듃由?二쇨린??媛깆떊 (30珥?湲곕낯媛?
  - ?ㅼ슫 ???먮룞 ?쒓굅

### ?ㅼ젙 諛??묒냽
```bash
# Eureka ??쒕낫??(?ㅼ떆媛??쒕퉬???곹깭 紐⑤땲?곕쭅)
http://localhost:8761

# ?깅줉???쒕퉬???뺤씤
curl http://localhost:8761/eureka/apps

# ingest-service ?곸꽭 ?뺣낫
curl http://localhost:8761/eureka/apps/ingest-service

# consumer-worker ?곸꽭 ?뺣낫
curl http://localhost:8761/eureka/apps/consumer-worker
```

### ?섍꼍 蹂??
```yaml
EUREKA_SERVER_URL: http://eureka-server:8761/eureka/  # Eureka ?쒕쾭 二쇱냼
```

### 二쇱슂 ?뚯씪
- `backend/eureka-server/src/main/java/com/example/eureka/EurekaServerApplication.java`: Eureka Server 援ы쁽
- `backend/eureka-server/src/main/resources/application.yml`: Eureka ?ㅼ젙
- `backend/ingest-service/src/main/resources/application.yml` (L106-114): Eureka Client ?ㅼ젙
- `backend/consumer-worker/src/main/resources/application.yml` (L48-55): Eureka Client ?ㅼ젙

### Phase 5 ?ㅼ??쇰쭅 ?쒖슜
Eureka??3媛??쒕쾭 援ъ“?먯꽌 ?ㅼ쓬怨?媛숈씠 ?쒖슜??
- Server 1 (API): ingest-service ??Eureka???깅줉
- Server 2 (Data): consumer-worker ??Eureka???깅줉
- Server 3 (Infra): eureka-server ??以묒븰 ?덉??ㅽ듃由??댁쁺
- API Gateway (異뷀썑): Eureka瑜??듯빐 ?쒕퉬???숈쟻 ?쇱슦??媛??

---

## Circuit Breaker (Resilience4j)

Kafka 諛쒗뻾 ?ㅽ뙣濡쒕????쒖뒪?쒖쓣 蹂댄샇?섎뒗 ?꾨줈?뺤뀡 ?섏???Circuit Breaker 援ы쁽?? Eureka Service Discovery? ?④퍡 ?묐룞?섏뿬 ?쒕퉬???덉쭏由ъ뼵??媛뺥솕.

### 媛쒖슂
- **?꾨젅?꾩썙??*: Resilience4j 2.1.0
- **蹂댄샇 ???*: Kafka Publisher (ingest-service)
- **?곹깭 愿由?*: CLOSED ??OPEN ??HALF_OPEN ??CLOSED
- **?먮룞 蹂듦뎄**: ?섏〈???뚮났 ???먮룞?쇰줈 ?쒕퉬??蹂듦뎄
- **Eureka ?듯빀**: Circuit Breaker ?곹깭瑜?Eureka Health Indicator濡??몄텧?섏뿬 ?쒕퉬???곹깭 紐⑤땲?곕쭅 媛??

### 紐⑤땲?곕쭅
```
Circuit Breaker ?곹깭 ?뺤씤:
curl http://localhost:8080/circuit-breaker/kafka-publisher

?묐떟 ?덉떆:
{
  "state": "CLOSED",
  "numberOfSuccessfulCalls": 25,
  "numberOfSlowCalls": 11,
  "slowCallRate": "44.00%",
  "failureRate": "0.00%"
}
```

### ?먮룞 ?뚯뒪??
```bash
# ?꾩껜 Circuit Breaker ?쒕굹由ъ삤 ?먮룞 ?ㅽ뻾 (9?④퀎)
bash scripts/test-circuit-breaker.sh

# Jenkins ?뚯씠?꾨씪?몄뿉???먮룞 ?ㅽ뻾??
# Smoke Test ?ㅼ쓬 "Circuit Breaker Test" ?④퀎 ?ы븿
```

### ?ㅼ떆媛?紐⑤땲?곕쭅
- **Prometheus**: http://localhost:9090 ??荑쇰━ 寃????`resilience4j_circuitbreaker_state`
- **Grafana**: http://localhost:3000 ??Dashboards ??"Payment Service Overview" ??Circuit Breaker ?⑤꼸

### ?곹깭蹂??숈옉
| ?곹깭 | ?섎? | ?숈옉 |
|------|------|------|
| **CLOSED (0)** | ?뺤긽 | 紐⑤뱺 ?붿껌 ?듦낵, 硫뷀듃由?湲곕줉 |
| **OPEN (1)** | ?μ븷 媛먯? | ?붿껌 利됱떆 李⑤떒 (30珥??湲? |
| **HALF_OPEN (2)** | 蹂듦뎄 ?쒕룄 | ?쒗븳???붿껌?쇰줈 ?곹깭 ?뺤씤 (理쒕? 3媛? |
| **DISABLED (3)** | 鍮꾪솢?깊솕 | ??긽 ?붿껌 ?듦낵, 硫뷀듃由?쭔 湲곕줉 |
| **FORCED_OPEN (4)** | 媛뺤젣 李⑤떒 | ?몃? 紐낅졊?쇰줈 李⑤떒 (愿由?紐⑹쟻) |
| **FORCED_HALF_OPEN (5)** | 媛뺤젣 蹂듦뎄 | ?몃? 紐낅졊?쇰줈 蹂듦뎄 ?쒕룄 (?뚯뒪???⑸룄) |

### 愿??臾몄꽌
- **?꾩쟾??媛?대뱶**: [CIRCUIT_BREAKER_GUIDE.md](CIRCUIT_BREAKER_GUIDE.md)
  - 援ы쁽 ?곸꽭 ?ㅻ챸
  - ?섎룞 ?뚯뒪??諛⑸쾿
  - 紐⑤땲?곕쭅 ?ㅼ젙
  - ?몃윭釉붿뒋??

---

## Service Discovery (Eureka)

留덉씠?щ줈?쒕퉬???꾪궎?띿쿂?먯꽌 ?쒕퉬?ㅻ뱾???먮룞?쇰줈 ?쒕줈瑜?李얠쓣 ???덈룄濡??섎뒗 以묒븰 ?덉??ㅽ듃由?

### 媛쒖슂
- **?쒕쾭**: Eureka Server (?ы듃 8761)
  - Spring Cloud Netflix Eureka Server 4.1.1
  - ?쒕퉬???깅줉/議고쉶 ?대떦
  - Self-preservation 鍮꾪솢?깊솕 (媛쒕컻 ?섍꼍)

- **?대씪?댁뼵??*: ingest-service, consumer-worker
  - ?먮룞 ?쒕퉬???깅줉 (IP 湲곕컲)
  - 30珥?二쇨린 heartbeat
  - ?ㅼ슫 ???먮룞 ?쒓굅

### ?묒냽 諛??뺤씤
```bash
# Eureka ??쒕낫??(?ㅼ떆媛??쒕퉬???곹깭)
http://localhost:8761

# ?깅줉???꾩껜 ?쒕퉬??議고쉶
curl http://localhost:8761/eureka/apps

# ingest-service ?곸꽭 ?뺣낫
curl http://localhost:8761/eureka/apps/INGEST-SERVICE

# consumer-worker ?곸꽭 ?뺣낫
curl http://localhost:8761/eureka/apps/CONSUMER-WORKER
```

### ?ㅼ젙
```yaml
# docker-compose.yml ?섍꼍 蹂??
EUREKA_SERVER_URL: http://eureka-server:8761/eureka/

# application.yml (?대씪?댁뼵???ㅼ젙)
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka/}
    register-with-eureka: true    # ?먯떊???덉??ㅽ듃由ъ뿉 ?깅줉
    fetch-registry: true           # ?덉??ㅽ듃由?二쇨린??媛깆떊
  instance:
    prefer-ip-address: true        # IP 二쇱냼 湲곕컲 ?깅줉
```

### Phase 5 ?ㅼ??쇰쭅 ?쒖슜
- **Server 1 (API)**: ingest-service ?깅줉 ??Eureka 議고쉶濡?downstream 諛쒓껄
- **Server 2 (Data)**: consumer-worker ?깅줉
- **Server 3 (Infra)**: eureka-server 以묒븰 ?댁쁺
- **API Gateway (異뷀썑)**: Eureka 湲곕컲 ?숈쟻 ?쇱슦??媛??

---

## Jenkins ?뚯씠?꾨씪??媛쒖슂
1. ?뚯뒪 泥댄겕?꾩썐
2. ?꾨윴?몄뿏??鍮뚮뱶 (npm install + vite build)
3. 諛깆뿏??鍮뚮뱶 (Gradle build)
4. Docker Compose 湲곕룞 (Prometheus/Grafana ?ы븿)
5. ?ъ뒪 泥댄겕 (理쒕? 60珥??ъ떆??
6. Smoke Test ?ㅽ뻾 (?ㅼ젣 寃곗젣 ?뱀씤 ?붿껌?쇰줈 E2E 寃利?
7. ?뚯씠?꾨씪??醫낅즺 ??docker compose down (AUTO_CLEANUP ?뚮씪誘명꽣媛 true??寃쎌슦)

### Jenkins ?뚮씪誘명꽣
- **AUTO_CLEANUP** (湲곕낯媛? false): 鍮뚮뱶 ?꾨즺 ??`docker compose down` ?ㅽ뻾 ?щ?
  - 泥댄겕?섏? ?딆쑝硫??쒕퉬?ㅺ? 怨꾩냽 ?ㅽ뻾?섏뼱 濡쒖뺄?먯꽌 ?묒냽/?뚯뒪??媛??
  - 泥댄겕?섎㈃ 鍮뚮뱶 ?꾨즺 ???먮룞?쇰줈 而⑦뀒?대꼫 ?뺣━

## ?깅뒫 理쒖쟻???댁뿭

### ?곗씠?곕쿋?댁뒪 ?쒕떇 (MariaDB)
`docker-compose.yml`?먯꽌 怨좊???泥섎━瑜??꾪븳 ?ㅼ젙???곸슜??
```yaml
mariadb:
  command:
    - --max-connections=200              # ?숈떆 ?곌껐 ??利앷?
    - --innodb-buffer-pool-size=512M     # 踰꾪띁 ? ?뺣?
    - --innodb-log-file-size=128M        # 濡쒓렇 ?뚯씪 ?ш린 利앷?
    - --innodb-flush-log-at-trx-commit=2 # ?깅뒫 ?μ긽 (?닿뎄???쎄컙 媛먯냼)
```

### 而ㅻ꽖??? 理쒖쟻??(ingest-service)
HikariCP ?ㅼ젙??議곗젙?댁꽌 200 RPS瑜?泥섎━??
```yaml
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 100  # 湲곕낯 10 ??100
SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: 20        # 湲곕낯 10 ??20
SERVER_TOMCAT_THREADS_MAX: 400                   # 湲곕낯 200 ??400
```

### Rate Limit ?④퀎蹂??ㅼ젙
遺???뚯뒪?몃? ?꾪빐 Rate Limit???④퀎?곸쑝濡??ㅼ젙??
- **媛쒕컻 ?섍꼍**: 1,000/1,000/500 (遺꾨떦, authorize/capture/refund)
- **遺???뚯뒪??*: 15,000/15,000/7,500 (200 RPS 紐⑺몴 + 25% ?ъ쑀遺?
- **?댁쁺 紐⑺몴**: 70,000/70,000/35,000 (1,000 TPS 紐⑺몴)

## ?몃윭釉붿뒋??

### 1. Prometheus 而⑦뀒?대꼫 ?쒖옉 ?ㅽ뙣
**臾몄젣**: Docker 蹂쇰ⅷ 留덉슫???ㅻ쪟 諛쒖깮
```
error mounting "/var/jenkins_home/.../prometheus.yml" to rootfs:
cannot create subdirectories... not a directory
```

**?먯씤**: Jenkins workspace?먯꽌 ?⑥씪 ?뚯씪??而⑦뀒?대꼫??留덉슫?명븷 ???붾젆?좊━濡??몄떇?섎뒗 Docker 踰꾧렇

**?닿껐**: 而ㅼ뒪? Dockerfile濡??ㅼ젙 ?뚯씪???대?吏??吏곸젒 ?ы븿??
```dockerfile
FROM prom/prometheus:v2.54.1
COPY prometheus.yml /etc/prometheus/prometheus.yml
EXPOSE 9090
CMD ["--config.file=/etc/prometheus/prometheus.yml"]
```

`docker-compose.yml` ?섏젙:
```yaml
prometheus:
  build:
    context: ./monitoring/prometheus
    dockerfile: Dockerfile
  image: pay-prometheus:local
```

### 2. Grafana ??쒕낫??誘명몴??
**臾몄젣**: Grafana UI?먯꽌 "Payment Service Overview" ??쒕낫?쒓? ?섑??섏? ?딆쓬

**?먯씤**: 蹂쇰ⅷ 留덉슫?몃줈 ?꾨떖??provisioning ?붾젆?좊━? ??쒕낫???뚯씪??而⑦뀒?대꼫 ?대??먯꽌 鍮??붾젆?좊━濡??앹꽦??

**?닿껐**: 而ㅼ뒪? Dockerfile濡?紐⑤뱺 ?ㅼ젙 ?뚯씪???대?吏???ы븿??
```dockerfile
FROM grafana/grafana:10.4.3
COPY provisioning/datasources /etc/grafana/provisioning/datasources
COPY provisioning/dashboards /etc/grafana/provisioning/dashboards
COPY dashboards /etc/grafana/dashboards
EXPOSE 3000
```

### 3. k6 遺???뚯뒪??85% ?ㅽ뙣??
**臾몄젣**: 珥덇린 ?뚯뒪?몄뿉??48,599嫄??붿껌 以?41,765嫄??ㅽ뙣 (85.93%)

**?먯씤 遺꾩꽍**:
- Rate Limit: 1,000/min (遺꾨떦 ~16.6 RPS)
- k6 ?ㅼ젣 遺?? 7,560/min (126 RPS)
- Rate Limit 珥덇낵濡??遺遺??붿껌 嫄곕???

**?닿껐 怨쇱젙**:
1. **臾몄젣 ?몄떇**: 臾댁옉??Rate Limit???믪씠??寃껋? ?ㅼ쟾怨??숇뼥?댁쭚
2. **紐⑺몴 ?ㅼ젙**: ?꾩옱 200 RPS, 理쒖쥌 1,000 TPS 泥섎━
3. **洹좏삎?≫엺 ?묎렐**:
   - Rate Limit: 15,000/min (250 RPS, 25% ?ъ쑀遺?
   - DB ?곌껐 ???利앷???
   - MariaDB ?깅뒫???쒕떇??
   - k6 ?쒕굹由ъ삤瑜??먯쭊??ramp-up?쇰줈 ?섏젙??

**k6 ?쒕굹由ъ삤 媛쒖꽑**:
```javascript
stages: [
  { duration: "30s", target: 50 },   // Warm-up
  { duration: "1m", target: 100 },   // Ramp-up
  { duration: "2m", target: 150 },   // Increase
  { duration: "2m", target: 200 },   // Target
  { duration: "2m", target: 200 },   // Sustain
  { duration: "30s", target: 0 },    // Cool-down
]
```

## DLQ (Dead Letter Queue) ?뚯뒪??

### ?뚯뒪??紐⑹쟻
Consumer 泥섎━ ?ㅽ뙣 ??DLQ濡?硫붿떆吏媛 ?꾩넚?섎뒗吏 寃利?

### ?뚯뒪??諛⑸쾿

#### 諛⑸쾿 1: 議댁옱?섏? ?딅뒗 Payment ID (FK ?쒖빟議곌굔 ?꾨컲)
```bash
# ?섎せ??paymentId濡??대깽???꾩넚
echo '{"paymentId":99999,"amount":10000,"occurredAt":"2025-01-01T00:00:00Z"}' | \
docker exec -i pay-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payment.captured

# DLQ ?뺤씤
docker exec pay-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment.dlq \
  --from-beginning
```

#### 諛⑸쾿 2: JSON ?뚯떛 ?ㅽ뙣
```bash
# ?섎せ??JSON ?꾩넚
echo 'invalid json data' | \
docker exec -i pay-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payment.captured

# DLQ ?뺤씤 (?꾩? ?숈씪)
```

#### 諛⑸쾿 3: ?꾨줎?몄뿏??+ DB 以묒? (?ㅼ쟾 ?쒕굹由ъ삤)
```bash
# 1. http://localhost:5173 ?먯꽌 ?뺤긽 寃곗젣 吏꾪뻾

# 2. MariaDB 以묒?
docker stop pay-mariadb

# 3. ?꾨줎?몄뿏?쒖뿉???ㅼ떆 寃곗젣 ?쒕룄 (?뱀씤? ?깃났, Capture ?대깽??諛쒗뻾)

# 4. Consumer 濡쒓렇 ?뺤씤 (DB ?곌껐 ?ㅽ뙣)
docker logs -f payment_swelite-consumer-worker-1

# 5. MariaDB ?ъ떆??
docker start pay-mariadb

# 6. DLQ ?뺤씤
docker exec pay-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment.dlq \
  --from-beginning
```

### DLQ 硫붿떆吏 援ъ“
```json
{
  "originalTopic": "payment.captured",
  "partition": 0,
  "offset": 123,
  "payload": "{?먮낯 ?대깽??",
  "errorType": "DataIntegrityViolationException",
  "errorMessage": "?곸꽭 ?먮윭 硫붿떆吏",
  "timestamp": "2025-10-21T..."
}
```

## ?ν썑 怨꾪쉷
- Settlement/Reconciliation ?鍮?鍮꾨룞湲?泥섎━ 蹂닿컯
- 異붽? ??쒕낫???뚮엺 援ъ꽦 諛??댁쁺 ?덉젙??
- 1,000 TPS 紐⑺몴瑜??꾪븳 異붽? ?ㅼ??쇰쭅 諛?理쒖쟻??

## GitHub ?뱁썒 ?먮룞??

### 媛쒖슂
濡쒖뺄 Jenkins ?몄뒪?댁뒪瑜??몃????몄텧?섏뿬 GitHub?먯꽌 /github-webhook/ ?붾뱶?ъ씤?몄뿉 ?묎렐?????덈룄濡??섍퀬, 紐⑤뱺 ?몄떆?먯꽌 ?뚯씠?꾨씪?몄쓣 ?몃━嫄고븿.

### ?④퀎蹂??ㅼ젙
1. **ngrok ?좏겙 ?ㅼ젙**
   - ?꾨줈?앺듃 猷⑦듃??`.env` ?뚯씪 ?앹꽦: `NGROK_AUTHTOKEN=<your-token>`
   - ??μ냼??`.gitignore`???대? `.env`媛 ?쒖쇅?섏뼱 ?덉쑝誘濡??덉쟾??
2. **ngrok ?꾨줈?꾨줈 Jenkins ?쒖옉**
   ```bash
   docker compose --profile ngrok up -d jenkins ngrok
   ```
   ngrok 而⑦뀒?대꼫??GitHub???몃옒?쎌쓣 `pay-jenkins:8080`?쇰줈 ?꾨떖?섍퀬 濡쒖뺄 寃?ш린 UI瑜?`http://localhost:4040`???몄텧??
3. **GitHub ?뱁썒 ?ㅼ젙**
   - `http://localhost:4040` ?닿퀬 HTTPS ?ъ썙??URL 蹂듭궗 (?? `https://abcd1234.ngrok.io`)
   - GitHub ??μ냼 ?뱁썒 URL??`https://abcd1234.ngrok.io/github-webhook/`濡??ㅼ젙?섍퀬 "Push ?대깽?몃쭔" ?듭뀡 ?좏깮
4. **Jenkins ?뚯씠?꾨씪???몃━嫄?*
   - `Jenkinsfile`???대? `githubPush()` ?ы븿?섏뼱 ?덉쑝誘濡?ngrok???듯빐 ?ㅼ뼱?ㅻ뒗 紐⑤뱺 ?몄떆媛 ?먮룞?쇰줈 ?뚯씠?꾨씪???쒖옉??

> **蹂댁븞 二쇱쓽**: `.env` ?뚯씪? `.gitignore`???대? ?쒖쇅?섏뼱 ?덉쑝誘濡??덉쟾?? ?묒뾽 ?꾨즺 ??`docker compose down ngrok`?쇰줈 ngrok 而⑦뀒?대꼫 醫낅즺 ?먮뒗 ?꾩껜 ?ㅽ깮 以묐떒.
