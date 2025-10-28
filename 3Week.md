# 3二쇱감 ?묒뾽 ?붿빟

## 1. Circuit Breaker (Resilience4j) 援ы쁽

### ?꾩슂??
Kafka ?쇰툝由ъ뀛媛 ?μ븷瑜?留뚮굹???꾩껜 ?쒖뒪?쒖쓣 蹂댄샇?댁빞 ?섍린 ?뚮Ц??Circuit Breaker ?꾩엯.

### 援ы쁽 ?댁슜
- **Resilience4j 2.1.0** 湲곕컲 Circuit Breaker
- `PaymentEventPublisher`?먯꽌 Kafka 諛쒗뻾 蹂댄샇
- ?곹깭: CLOSED ??OPEN ??HALF_OPEN ??CLOSED
- ?ㅼ젙:
  - ?ㅽ뙣???먮┛?몄텧??>= 50% ??OPEN
  - ?먮┛?몄텧 ?먯젙 >= 5珥?
  - 理쒖냼 5媛??몄텧 ???먯젙
  - OPEN ??HALF_OPEN ?湲? 30珥?
  - HALF_OPEN ?뚯뒪?? 理쒕? 3媛??붿껌

### 援ы쁽 ?뚯씪
- `backend/ingest-service/src/main/java/com/example/payment/service/PaymentEventPublisher.java`
- `backend/ingest-service/src/main/java/com/example/payment/config/Resilience4jConfig.java`
- `backend/ingest-service/src/main/resources/application.yml` (L82-95)
- `backend/ingest-service/src/main/java/com/example/payment/web/CircuitBreakerStatusController.java`

### 紐⑤땲?곕쭅
```bash
# Circuit Breaker ?곹깭 議고쉶
curl http://localhost:8080/circuit-breaker/kafka-publisher

# Prometheus 硫뷀듃由?
http://localhost:9090/graph ??resilience4j_circuitbreaker_state

# Grafana ??쒕낫??
http://localhost:3000 ??Payment Service Overview ??Circuit Breaker State ?⑤꼸
```

### ?먮룞 ?뚯뒪??
```bash
bash scripts/test-circuit-breaker.sh
# 9?④퀎 ?먮룞 ?쒕굹由ъ삤: warm-up ??Kafka 以묐떒 ???먮┛ ?붿껌 ??Kafka ?ъ떆????蹂듦뎄
```

---

## 2. Service Discovery (Eureka) ?꾩엯

### ?꾩슂??
留덉씠?щ줈?쒕퉬???뺤옣(Phase 5)???鍮꾪빐 ?쒕퉬??媛??먮룞 諛쒓껄 硫붿빱?덉쬁 ?꾩슂.

### 援ы쁽 ?댁슜

#### Eureka Server
- Added Spring Cloud Gateway (port 8080) with `/api/payments/**` route pointing to `INGEST-SERVICE` via Eureka, acting as the single public entry point.
- ?덈줈??紐⑤뱢: `backend/eureka-server`
- Spring Cloud Netflix Eureka Server 4.1.1
- ?ы듃: 8761
- Self-preservation 鍮꾪솢?깊솕 (媛쒕컻 ?섍꼍)
- Health/Metrics ?붾뱶?ъ씤???몄텧 (Prometheus ?곕룞)

#### Eureka Client
- **ingest-service**: ?먮룞 ?깅줉 (IP 湲곕컲)
- **consumer-worker**: ?먮룞 ?깅줉 (IP 湲곕컲)
- ?ㅼ젙:
  - `register-with-eureka: true`
  - `fetch-registry: true`
  - `prefer-ip-address: true`
  - Heartbeat: 30珥?二쇨린

#### docker-compose.yml
- eureka-server ?쒕퉬??異붽? (?ы듃 8761)
- ingest-service/consumer-worker?먯꽌 `EUREKA_SERVER_URL` ?섍꼍 蹂???ㅼ젙
- depends_on?쇰줈 ?쒖옉 ?쒖꽌 蹂댁옣

### 援ы쁽 ?뚯씪
- `backend/eureka-server/src/main/java/com/example/eureka/EurekaServerApplication.java`
- `backend/eureka-server/src/main/resources/application.yml`
- `backend/ingest-service/src/main/resources/application.yml` (L106-114)
- `backend/consumer-worker/src/main/resources/application.yml` (L48-55)
- `backend/eureka-server/build.gradle.kts` (spring-cloud-starter-netflix-eureka-server 4.1.1)
- `backend/ingest-service/build.gradle.kts` (spring-cloud-starter-netflix-eureka-client 4.1.1)
- `backend/consumer-worker/build.gradle.kts` (spring-cloud-starter-netflix-eureka-client 4.1.1)

### ?뺤씤 諛⑸쾿
```bash
# Eureka ??쒕낫??
http://localhost:8761

# ?깅줉???쒕퉬??議고쉶
curl http://localhost:8761/eureka/apps
curl http://localhost:8761/eureka/apps/INGEST-SERVICE
curl http://localhost:8761/eureka/apps/CONSUMER-WORKER

# Prometheus 硫뷀듃由?
http://localhost:9090 ??eureka 愿??硫뷀듃由??뺤씤
```

---

## 3. Jenkins & GitHub Webhook ?먮룞??

### 援ы쁽 ?댁슜
- Jenkins ?뚯씠?꾨씪?몄뿉 **"Circuit Breaker Test"** ?④퀎 異붽? (Smoke Test ?댄썑)
- ngrok ?꾨줈??異붽?: `docker compose --profile ngrok up -d`
- `.env` ?뚯씪??`NGROK_AUTHTOKEN` ?ㅼ젙
- GitHub Webhook: `https://<ngrok-url>/github-webhook/`

### ?뚯씠?꾨씪???④퀎
1. ?뚯뒪 泥댄겕?꾩썐
2. Frontend 鍮뚮뱶 (npm)
3. Backend 鍮뚮뱶 (Gradle)
4. Docker Compose 諛고룷
5. Health Check
6. Smoke Test
7. **Circuit Breaker Test** (?먮룞 ?ㅽ겕由쏀듃)

### ?뚯뒪???ㅽ겕由쏀듃
- `scripts/test-circuit-breaker.sh` 由ы뙥?곕쭅
- Docker Compose ?ㅽ듃?뚰겕 ?대? ?ㅽ뻾 (`docker compose exec`)
- 9?④퀎 ?먮룞 寃利?

---

## 4. Grafana ??쒕낫??媛뺥솕

### Circuit Breaker ?⑤꼸
- ?곹깭 ???6媛? CLOSED / OPEN / HALF_OPEN / DISABLED / FORCED_OPEN / FORCED_HALF_OPEN
- ?쒖꽦 ?곹깭留?珥덈줉??
- ?먮┛?몄텧 鍮꾩쑉 & ?ㅽ뙣??Stat
- ?몄텧 ??異붿씠 Time Series

### 援ы쁽 ?뚯씪
- `monitoring/grafana/dashboards/payment-overview.json`

### Grafana ?묒냽
```bash
http://localhost:3000 (admin/admin)
Dashboards ??Payment Service Overview
```

---

## 5. 臾몄꽌??

### ?묒꽦/?섏젙??臾몄꽌
- `README.md`: Circuit Breaker & Eureka ?뱀뀡 異붽?
- `CIRCUIT_BREAKER_GUIDE.md`: ?꾩쟾??媛?대뱶 (524以?
- `3Week.md`: 二쇨컙 蹂寃?濡쒓렇
- `docker-compose.yml`: eureka-server ?쒕퉬??異붽?

### 二쇱슂 ?댁슜
- Circuit Breaker ?곹깭 ?꾩씠 ?ㅼ씠?닿렇??
- Eureka ?깅줉/議고쉶 ?꾨줈?몄뒪
- ?섎룞/?먮룞 ?뚯뒪??諛⑸쾿
- Phase 5 ?뺤옣 怨꾪쉷

---

## 6. ?ъ꽦??紐⑺몴

- ??Circuit Breaker ?꾨줈?뺤뀡 ?섏? 援ы쁽
- ??Eureka Service Discovery ?꾩쟾 ?듯빀
- ???먮룞?붾맂 ?뚯뒪???ㅽ겕由쏀듃 (9?④퀎)
- ??GitHub Webhook + Jenkins ?곕룞
- ???ㅼ떆媛?紐⑤땲?곕쭅 (Prometheus/Grafana)
- ??200 RPS ?덉젙??泥섎━
- ??Phase 5 ?ㅼ??쇰쭅 以鍮??꾨즺

---

## 7. ?ㅼ쓬 ?묒뾽

### Phase 4: API Gateway & Load Balancing
- Spring Cloud Gateway ?꾩엯
- Eureka 湲곕컲 ?숈쟻 ?쇱슦??
- Client-side 濡쒕뱶 諛몃윴??(LoadBalancer)

### Phase 5: Service Mesh
- Istio vs Linkerd ?됯?
- ?몃옒??愿由?(Virtual Service, Destination Rule)
- 蹂댁븞 (mTLS, Authorization Policy)
- 紐⑤땲?곕쭅 (Jaeger, Kiali)

### ?깅뒫 ?뺤옣
- Phase 3: DB ?몃뜳?? Kafka 諛곗튂 泥섎━
- Phase 4: KT Cloud ?⑥씪 ?쒕쾭 諛고룷 (400 RPS)
- Phase 5: 3媛??쒕쾭 怨꾩링 遺꾨━ (1000 RPS)

---

## 8. ?뚯뒪??泥댄겕由ъ뒪??

### Circuit Breaker
- [ ] ?쒕퉬???쒖옉: `docker compose up -d`
- [ ] Eureka ??쒕낫???뺤씤: http://localhost:8761
- [ ] ?뺤긽 ?붿껌 5媛??꾩넚
- [ ] Kafka 以묐떒 ???먮┛ ?붿껌 6媛?
- [ ] Circuit Breaker state = OPEN/HALF_OPEN ?뺤씤
- [ ] Kafka ?ъ떆????蹂듦뎄 ?뺤씤
- [ ] 理쒖쥌 ?곹깭 = CLOSED

### Eureka
- [ ] ingest-service UP ?곹깭 ?뺤씤
- [ ] consumer-worker UP ?곹깭 ?뺤씤
- [ ] `/eureka/apps` API ?묐떟 ?뺤씤
- [ ] ?쒕퉬???대졇???ㅼ떆 ?щ젮???곹깭 蹂寃??뺤씤 (30珥??대궡)

### Jenkins
- [ ] GitHub push ???먮룞 鍮뚮뱶 (ngrok ?듯빐)
- [ ] 紐⑤뱺 ?④퀎 ?듦낵
- [ ] Circuit Breaker Test ?뺤긽 ?꾨즺
- [ ] Eureka Server ?쒖옉 ?뺤씤
