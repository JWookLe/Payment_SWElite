# Payment_SWElite

## ì£¼ì°¨ë³?ëª©í‘œ

- **1ì£¼ì°¨**
  - React ëª©ì—… ?ì ê³?Spring Boot ê¸°ë°˜ ê²°ì œ API(?¹ì¸Â·?•ì‚°Â·?˜ë¶ˆ)ë¡?E2E ?Œë¡œ??êµ¬í˜„
  - Kafka, Redis, MariaDB, Jenkinsë¥??¬í•¨??Docker Compose ë¡œì»¬ ?˜ê²½ êµ¬ì¶•
- **2ì£¼ì°¨**
  - [x] Redis ±â¹İ rate limit ¹× ¸èµî Ä³½Ã °íµµÈ­
  - [x] Prometheus + Grafana ÁöÇ¥ ¼öÁı ¹× ½Ã°¢È­ ÆÄÀÌÇÁ¶óÀÎ ±¸¼º
  - [ ] k6 ºÎÇÏ/½ºÆ®·¹½º Å×½ºÆ® ½Ã³ª¸®¿À ÀÛ¼º ¹× Jenkins ¸®Æ÷Æ® ÀÚµ¿È­
  - [ ] Settlement/Reconciliation ´ëºñ ºñµ¿±â Ã³¸® º¸°­
  - [x] payment.dlq ÅäÇÈ ÀçÀü¼Û ±â¹İ Consumer ¿¹¿Ü Ã³¸® º¸°­

| êµ¬ì„± | ?¤ëª… |
| --- | --- |
| **frontend** | React + Viteë¡??‘ì„±??ëª©ì—… ?ì  UI. iPhone 16 Pro, Galaxy S25 Ultra, Xiaomi 14T Proë¥??€?ìœ¼ë¡??ŒìŠ¤???œë‚˜ë¦¬ì˜¤ ?œê³µ. |
| **ingest-service** | Spring Boot(Java 21) ê¸°ë°˜ ê²°ì œ API. ?¹ì¸/?•ì‚°/?˜ë¶ˆ ì²˜ë¦¬?€ outbox ?´ë²¤??ë°œí–‰ ?´ë‹¹. |
| **consumer-worker** | Kafka Consumer. ê²°ì œ ?´ë²¤?¸ë? ?˜ì‹ ??ledger ?°ì´?°ë? ë³´ê°•?˜ê³  ?„ì† ì²˜ë¦¬ë¥?ì¤€ë¹? |
| **mariadb** | ë©”ì¸ ?°ì´?°ë² ?´ìŠ¤(paydb). payment, ledger_entry, outbox_event, idem_response_cache ?Œì´ë¸”ì„ ê´€ë¦? |
| **kafka & zookeeper** | ê²°ì œ ?´ë²¤??? í”½(`payment.authorized`, `payment.captured`, `payment.refunded`)???¸ìŠ¤?? |
| **redis** | rate limitê³?ë©±ë“± ?‘ë‹µ ìºì‹œë¥??´ëŠ” in-memory ?¤í† ë¦¬ì?. |
| **jenkins** | Gradle + NPM ë¹Œë“œ?€ Docker Compose ë°°í¬ë¥??ë™?”í•˜??CI ?œë²„. |

## ì£¼ìš” ?Œì´ë¸?DDL

`backend/ingest-service/src/main/resources/schema.sql`?ì„œ ?•ì¸?????ˆëŠ” ?µì‹¬ ?Œì´ë¸”ì? ?¤ìŒê³?ê°™ìŠµ?ˆë‹¤.

- `payment` : ê²°ì œ ?íƒœ ë°?ë©±ë“± ??ê´€ë¦?- `ledger_entry` : ?¹ì¸/?•ì‚°/?˜ë¶ˆ ???ì„±?˜ëŠ” ?ì¥ ?°ì´??- `outbox_event` : Kafka ë°œí–‰ ?€ê¸??´ë²¤???€??- `idem_response_cache` : ?¹ì¸ ?‘ë‹µ ë©±ë“± ìºì‹œ

## REST API ?”ì•½

| Method | Path | ?¤ëª… |
| --- | --- | --- |
| `POST` | `/payments/authorize` | ë©±ë“±?±ì„ ë³´ì¥?˜ëŠ” ?¹ì¸??ì²˜ë¦¬?˜ê³  `payment` ë°?`outbox_event`??ê¸°ë¡ |
| `POST` | `/payments/capture/{paymentId}` | ?¹ì¸??ê²°ì œë¥??•ì‚° ì²˜ë¦¬, ledger ê¸°ë¡, ?´ë²¤??ë°œí–‰ |
| `POST` | `/payments/refund/{paymentId}` | ?•ì‚°??ê²°ì œë¥??˜ë¶ˆ ì²˜ë¦¬, ledger ê¸°ë¡, ?´ë²¤??ë°œí–‰ |

?¤ë¥˜ ?‘ë‹µ?€ ?”êµ¬?¬í•­??ë§ì¶° `DUPLICATE_REQUEST`, `CAPTURE_CONFLICT`, `REFUND_CONFLICT`, `NOT_FOUND` ì½”ë“œë¥?ë°˜í™˜?©ë‹ˆ??

## Kafka ? í”½

- `payment.authorized`
- `payment.captured`
- `payment.refunded`
- `payment.dlq` (Consumer ?¤íŒ¨ ???¬ì „??

## Redis ê¸°ë°˜ ë³´í˜¸ ê¸°ëŠ¥

- ?¹ì¸ API ?‘ë‹µ?€ Redis TTL ìºì‹œ???™ì‹œ ?€?¥ë¼ ì¤‘ë³µ ?”ì²­ ??ë¹ ë¥´ê²??¬ì‚¬?©ë©?ˆë‹¤. ê¸°ë³¸ TTL?€ 600ì´ˆì´ë©?`APP_IDEMPOTENCY_CACHE_TTL_SECONDS` ?˜ê²½ ë³€?˜ë¡œ ì¡°ì •?????ˆìŠµ?ˆë‹¤.
- ?ì (`merchantId`) ?¨ìœ„ ?¹ì¸Â·?•ì‚°Â·?˜ë¶ˆ API??ë¶„ë‹¹ 20/40/20???ˆì´??ë¦¬ë°‹???ìš©?©ë‹ˆ?? `APP_RATE_LIMIT_*` ?˜ê²½ ë³€?˜ë¡œ ì¡°ì • ê°€?¥í•˜ê³? Redis ?¥ì•  ???œí•œ ?†ì´ ì²˜ë¦¬?˜ë„ë¡?fail-open?¼ë¡œ êµ¬ì„±?ˆìŠµ?ˆë‹¤.

## ë¡œì»¬ ?¤í–‰ ë°©ë²•

1. **Docker Compose ê¸°ë™**
   ```bash
   docker compose up --build
   ```
   MariaDB, Redis, Kafka, ingest-service, consumer-worker, frontendê°€ ?¨ê»˜ ê¸°ë™?©ë‹ˆ??

2. **?„ëŸ°?¸ì—”???‘ì†**
   - http://localhost:5173
   - ?í’ˆ, ?‰ìƒ, ?˜ëŸ‰??? íƒ?˜ê³  ê²°ì œ ?ŒìŠ¤???˜í–‰
   - ?±ê³µ ??authorize + capture ?‘ë‹µ JSON ?•ì¸

3. **?˜ë™ API ?ŒìŠ¤??*
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

4. **?œë¹„??ì¢…ë£Œ**
   ```bash
   docker compose down
   ```

## Jenkins ?Œì´?„ë¼??
`Jenkinsfile`?€ ?¤ìŒ ?¨ê³„ë¥??ë™?”í•©?ˆë‹¤.

1. ?ŒìŠ¤ ì²´í¬?„ì›ƒ
2. `frontend` ë¹Œë“œ (npm install + Vite build)
3. `backend` ë¹Œë“œ (Gradle clean build)
4. Docker ?´ë?ì§€ ë¹Œë“œ ë°?Compose ê¸°ë™
5. ê°„ë‹¨??`curl` ê¸°ë°˜ Smoke Test
6. ?Œì´?„ë¼??ì¢…ë£Œ ??Compose ?•ë¦¬

## ?¤ìŒ ?¨ê³„ ?œì•ˆ

- Prometheus + Grafana ì§€???˜ì§‘/?œê°??- k6 ë¶€???¤íŠ¸?ˆìŠ¤ ?ŒìŠ¤???œë‚˜ë¦¬ì˜¤ ë°?Jenkins ë¦¬í¬???ë™??- Settlement/Reconciliation ?œë¹„???Œì´ë¸?ì¶”ê? ë°?ë¹„ë™ê¸?ì²˜ë¦¬ ?•ì¥

## Observability (Prometheus & Grafana)

- Prometheus¿Í Grafana´Â `docker compose` ½ÇÇà ½Ã ÀÚµ¿À¸·Î ±âµ¿µË´Ï´Ù.
- Prometheus UI: http://localhost:9090 (ingest-service / consumer-workerÀÇ `/actuator/prometheus` ¿£µåÆ÷ÀÎÆ®¸¦ ½ºÅ©·¦)
- Grafana UI: http://localhost:3000 (±âº» °èÁ¤ `admin`/`admin`). Ã¹ ·Î±×ÀÎ ½Ã ºñ¹Ğ¹øÈ£ º¯°æÀ» ±ÇÀåÇÕ´Ï´Ù.
- Grafana¿¡´Â `Payment Service Overview` ±âº» ´ë½Ãº¸µå°¡ Æ÷ÇÔµÇ¾î API ¿äÃ» ¼Óµµ, p95 Áö¿¬½Ã°£, Kafka Consumer Ã³¸®·®À» È®ÀÎÇÒ ¼ö ÀÖ½À´Ï´Ù.


## Load Testing (k6)

- `loadtest/k6/payment-scenario.js` ½Ã³ª¸®¿À´Â ½ÂÀÎ¡æÁ¤»ê¡æ(¼±ÅÃÀû)È¯ºÒ±îÁö ÇÑ ¹ø¿¡ °ËÁõÇÕ´Ï´Ù.
- ±âº» ¿É¼ÇÀº 1ºĞ ¿ú¾÷ ÈÄ ÃÖ´ë ÃÊ´ç 30¿äÃ»±îÁö Áõ°¡ÇÕ´Ï´Ù. `BASE_URL` ¶Ç´Â `MERCHANT_ID`¸¦ È¯°æº¯¼ö·Î ¿À¹ö¶óÀÌµåÇÒ ¼ö ÀÖ½À´Ï´Ù.
- ·ÎÄÃ ½ÇÇà ¿¹½Ã:
  ```bash
  k6 run loadtest/k6/payment-scenario.js
  ```
- CI¿¡¼­´Â ÃßÈÄ Jenkins ÆÄÀÌÇÁ¶óÀÎÀ» ÅëÇØ ÀÚµ¿È­µË´Ï´Ù.

