# k6 부하 테스트 가이드

이 폴더는 결제 시스템에 대한 k6 부하 테스트 자산을 모아둔 자리입니다.  
`payment-scenario.js` 스크립트를 사용해 승인·정산·환불 흐름을 시뮬레이션하고, `summary.json`에 결과 요약을 남깁니다.

## 폴더 구성

```
loadtest/k6/
├── payment-scenario.js  # k6 시나리오 스크립트
├── summary.json         # 최신 실행 결과 (k6 --summary-export)
└── README.md            # 이 문서
```

## 시나리오 구성

`payment-scenario.js`는 한 번의 반복(iteration) 동안 다음 흐름을 수행합니다.

1. 결제 승인 API `/payments/authorize`
2. (옵션) 결제 정산 API `/payments/capture/{paymentId}`
3. (옵션) 결제 환불 API `/payments/refund/{paymentId}`

각 단계는 환경 변수로 켜고 끌 수 있습니다.

- `ENABLE_CAPTURE=true` → 정산 단계 실행
- `ENABLE_REFUND=true` → 환불 단계 실행 (정산이 켜져 있어야 의미 있음)

## 실행 방법

### 1. 스크립트 사용

루트에서 제공하는 헬퍼 스크립트로 시나리오를 선택해 실행할 수 있습니다.

```bash
# authorize-only | authorize-capture | full-flow
./scripts/run-k6-test.sh authorize-only
```

스크립트는 자동으로 Docker 네트워크(`payment_swelite_default`)를 사용하고, 실행이 끝나면 `loadtest/k6/summary.json`을 갱신합니다.

### 2. Docker로 직접 실행

```bash
docker run --rm \
  --network payment_swelite_default \
  -v "$PWD/loadtest/k6:/k6" \
  -e BASE_URL=http://ingest-service:8080 \
  -e MERCHANT_ID=K6TEST \
  -e ENABLE_CAPTURE=false \
  -e ENABLE_REFUND=false \
  grafana/k6:0.49.0 \
  run /k6/payment-scenario.js \
  --summary-export=/k6/summary.json
```

필요에 따라 `BASE_URL`을 게이트웨이나 외부 도메인으로 바꿔도 됩니다.

## 기본 부하 패턴

스크립트의 기본 옵션은 8분 동안 400 RPS까지 ramp-up 하는 구조입니다.  
`options.scenarios.authorize_flow.stages`를 수정하면 목표 RPS나 지속 시간을 조정할 수 있습니다.

예시:

```javascript
stages: [
  { duration: '30s', target: 100 },  // 워밍업
  { duration: '1m',  target: 200 },  // 1단계 램프업
  { duration: '2m',  target: 300 },  // 2단계 램프업
  { duration: '2m',  target: 400 },  // 목표 구간
  { duration: '2m',  target: 400 },  // 유지
  { duration: '30s', target: 0 },    // 쿨다운
],
```

400 RPS 이상을 목표로 할 때는 단계별 `target` 값을 더 늘리고, 백엔드 설정(레이트 리밋, Kafka 파티션 등)이 대응하도록 먼저 조정해 주세요.

## 성능 목표 예시

- 실패율 (`http_req_failed`) 5% 미만
- 승인 API p95 응답 시간 100 ms 미만 (`payment_authorize_duration`)
- 지속 구간 동안 목표 RPS 유지

## 결과 확인

1. `summary.json` 파일을 열어 핵심 지표(p90, p95, error rate 등)를 확인합니다.
2. 모니터링 서비스가 올라가 있다면 REST API로도 조회할 수 있습니다.
   ```bash
   curl http://localhost:8082/monitoring/loadtest/latest-result
   curl http://localhost:8082/monitoring/loadtest/analyze
   curl http://localhost:8082/monitoring/loadtest/history
   ```
3. Claude MCP를 사용 중이라면 `payment-database-mcp`나 `payment-redis-mcp`로 부하 이후 상태를 바로 점검할 수 있습니다.

