import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const ENABLE_CAPTURE = ((__ENV.ENABLE_CAPTURE || "false").toLowerCase() === "true");
const ENABLE_REFUND = ((__ENV.ENABLE_REFUND || "false").toLowerCase() === "true");

// 샤딩을 위한 랜덤 merchantId 생성 (1~1000 범위로 짝수/홀수 균등 분배)
function getRandomMerchantId() {
  return `MERCHANT-${Math.floor(Math.random() * 1000) + 1}`;
}

// Debug: log configuration at startup
console.log(`K6 Configuration: BASE_URL=${BASE_URL}, SHARDING_ENABLED=true`);

const authorizeTrend = new Trend("payment_authorize_duration", true);
const captureTrend = new Trend("payment_capture_duration", true);
const refundTrend = new Trend("payment_refund_duration", true);
const errorRate = new Rate("payment_errors");

// Track error samples for debugging
let errorSampleCount = 0;
const MAX_ERROR_SAMPLES = 10;

const thresholds = {
  http_req_failed: ["rate<0.05"],
  http_req_duration: ["p(95)<1000"],
  payment_errors: ["rate<0.002"],
  payment_authorize_duration: ["p(95)<500"],
};

if (ENABLE_CAPTURE) {
  thresholds.payment_capture_duration = ["p(95)<600"];
}
if (ENABLE_REFUND) {
  thresholds.payment_refund_duration = ["p(95)<700"];
}

export const options = {
  scenarios: {
    authorize_flow: {
      executor: "ramping-arrival-rate",
      startRate: 50,
      timeUnit: "1s",
      preAllocatedVUs: 400,
      maxVUs: 800,
      stages: [
        { duration: "30s", target: 200 },  // Warm-up: 200 RPS
        { duration: "1m", target: 400 },   // Ramp-up: 400 RPS
        { duration: "1m", target: 600 },   // Increase: 600 RPS
        { duration: "2m", target: 800 },   // Target: 800 RPS
        { duration: "2m", target: 800 },   // Sustain: 800 RPS
        { duration: "30s", target: 0 },    // Cool-down
      ],
    },
  },
  thresholds,
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
  group("authorize-capture-refund", () => {
    const idempotencyKey = buildIdempotencyKey();
    const amount = buildAmount();
    const merchantId = getRandomMerchantId();  // 샤딩을 위한 랜덤 merchantId

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
      // Log first few errors for debugging
      if (errorSampleCount < MAX_ERROR_SAMPLES) {
        errorSampleCount++;
        const bodyPreview = authorizeResponse.body ? authorizeResponse.body.substring(0, 200) : 'N/A';
        console.error(`ERROR #${errorSampleCount}: status=${authorizeResponse.status}, error=${authorizeResponse.error}, body=${bodyPreview}`);
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

    if (!ENABLE_CAPTURE && !ENABLE_REFUND) {
      return;
    }

    sleep(0.1);

    if (ENABLE_CAPTURE) {
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
      }

      if (!ENABLE_REFUND) {
        sleep(0.1);
        return;
      }

      sleep(0.1);
    }

    if (ENABLE_REFUND) {
      const refundPayload = JSON.stringify({
        merchantId: merchantId,
        reason: "k6 refund simulation",
      });

      const refundResponse = http.post(`${BASE_URL}/payments/refund/${paymentId}`, refundPayload, headers);
      refundTrend.add(refundResponse.timings.duration);

      const refundOk = check(refundResponse, {
        "refund status ok": (res) => res.status === 200 || res.status === 409,
      });

      if (!refundOk) {
        errorRate.add(1);
      }
    }

    // sleep(0.1); // Removed for accurate RPS testing
  });
}
