import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";

// 샤딩을 위한 랜덤 merchantId 생성 (1~1000 범위로 짝수/홀수 균등 분배)
function getRandomMerchantId() {
  return `MERCHANT-${Math.floor(Math.random() * 1000) + 1}`;
}

console.log(`K6 Full Flow Test: BASE_URL=${BASE_URL}`);

const authorizeTrend = new Trend("payment_authorize_duration", true);
const captureTrend = new Trend("payment_capture_duration", true);
const refundTrend = new Trend("payment_refund_duration", true);
const flowTrend = new Trend("payment_full_flow_duration", true);
const errorRate = new Rate("payment_errors");

// Track error samples for debugging
let errorSampleCount = 0;
const MAX_ERROR_SAMPLES = 10;

export const options = {
  scenarios: {
    full_flow: {
      // Full flow: authorize + capture + refund
      // 3 HTTP calls per iteration = 3x backend load
      // Reduced RPS to 350 (from 1050) to maintain similar backend throughput
      executor: "constant-arrival-rate",
      rate: 350,
      timeUnit: "1s",
      duration: "6m",
      preAllocatedVUs: 400,
      maxVUs: 800,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    // 단건 요청 지연: authorize/capture/refund 각각
    http_req_duration: ["p(95)<800"],
    payment_errors: ["rate<0.002"],
    // 전체 플로우: authorize + capture + refund + sleep(1s) 포함
    payment_full_flow_duration: ["p(95)<2800"],
    payment_authorize_duration: ["p(95)<500"],
    payment_capture_duration: ["p(95)<600"],
    payment_refund_duration: ["p(95)<700"],
  },
  summaryTrendStats: ["avg", "p(90)", "p(95)", "p(99)", "max"],
};

const headers = {
  headers: {
    "Content-Type": "application/json",
  },
};

function buildAmount() {
  const base = Math.floor(Math.random() * 50 + 1) * 1000;
  return Math.max(1000, base);
}

function buildIdempotencyKey() {
  return `k6-${__VU}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export default function () {
  group("full-flow", () => {
    const flowStart = Date.now();
    const idempotencyKey = buildIdempotencyKey();
    const amount = buildAmount();
    const merchantId = getRandomMerchantId();

    // Step 1: Authorize
    const authorizePayload = JSON.stringify({
      merchantId: merchantId,
      amount,
      currency: "KRW",
      idempotencyKey,
    });

    const authorizeResponse = http.post(`${BASE_URL}/payments/authorize`, authorizePayload, headers);
    authorizeTrend.add(authorizeResponse.timings.duration);

    const authorizeOk = check(authorizeResponse, {
      "authorize status ok": (res) => res.status === 200 || res.status === 409,
    });

    if (!authorizeOk) {
      errorRate.add(1);
      if (errorSampleCount < MAX_ERROR_SAMPLES) {
        errorSampleCount++;
        const bodyPreview = authorizeResponse.body ? authorizeResponse.body.substring(0, 200) : 'N/A';
        console.error(`ERROR #${errorSampleCount}: authorize failed - status=${authorizeResponse.status}, error=${authorizeResponse.error}, body=${bodyPreview}`);
      }
      return;
    }

    let paymentId;
    try {
      const payload = authorizeResponse.json();
      if (payload) {
        if (typeof payload.paymentId !== "undefined") {
          paymentId = payload.paymentId;
        } else if (payload.response && typeof payload.response.paymentId !== "undefined") {
          paymentId = payload.response.paymentId;
        }
      }
    } catch (error) {
      errorRate.add(1);
      return;
    }

    if (!paymentId) {
      errorRate.add(1);
      return;
    }

    // Wait for async processing (Transactional Outbox Pattern)
    // Authorization -> Outbox -> Kafka -> Query Service update
    sleep(0.5);

    // Step 2: Capture
    const capturePayload = JSON.stringify({
      merchantId: merchantId,
    });

    const captureResponse = http.post(`${BASE_URL}/payments/capture/${paymentId}`, capturePayload, headers);
    captureTrend.add(captureResponse.timings.duration);

    const captureOk = check(captureResponse, {
      "capture status ok": (res) => res.status === 200 || res.status === 409,
    });

    if (!captureOk) {
      errorRate.add(1);
      if (errorSampleCount < MAX_ERROR_SAMPLES) {
        errorSampleCount++;
        const bodyPreview = captureResponse.body ? captureResponse.body.substring(0, 200) : 'N/A';
        console.error(`ERROR #${errorSampleCount}: capture failed - status=${captureResponse.status}, body=${bodyPreview}`);
      }
      return;
    }

    // Wait for capture async processing
    sleep(0.5);

    // Step 3: Refund
    const refundPayload = JSON.stringify({
      merchantId: merchantId,
      reason: "k6 full flow test",
    });

    const refundResponse = http.post(`${BASE_URL}/payments/refund/${paymentId}`, refundPayload, headers);
    refundTrend.add(refundResponse.timings.duration);

    const refundOk = check(refundResponse, {
      "refund status ok": (res) => res.status === 200 || res.status === 409,
    });

    if (!refundOk) {
      errorRate.add(1);
      if (errorSampleCount < MAX_ERROR_SAMPLES) {
        errorSampleCount++;
        const bodyPreview = refundResponse.body ? refundResponse.body.substring(0, 200) : 'N/A';
        console.error(`ERROR #${errorSampleCount}: refund failed - status=${refundResponse.status}, body=${bodyPreview}`);
      }
      return;
    }

    // Mark successful iteration
    errorRate.add(0);
    flowTrend.add(Date.now() - flowStart);
  });
}
