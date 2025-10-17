import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANT_ID = __ENV.MERCHANT_ID || 'K6-MERCHANT';

const authorizeTrend = new Trend('payment_authorize_duration', true);
const captureTrend = new Trend('payment_capture_duration', true);
const refundTrend = new Trend('payment_refund_duration', true);
const errorRate = new Rate('payment_errors');

export const options = {
  scenarios: {
    peak_load: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: [
        { duration: '1m', target: 10 },
        { duration: '2m', target: 20 },
        { duration: '1m', target: 30 },
        { duration: '1m', target: 0 }
      ]
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
    payment_errors: ['rate<0.02'],
    payment_authorize_duration: ['p(95)<500'],
    payment_capture_duration: ['p(95)<600'],
    payment_refund_duration: ['p(95)<700']
  },
  summaryTrendStats: ['avg', 'p(90)', 'p(95)', 'p(99)', 'max']
};

const headers = {
  headers: {
    'Content-Type': 'application/json'
  }
};

function buildAmount() {
  const base = Math.floor(Math.random() * 50 + 1) * 1000;
  return Math.max(1000, base);
}

function buildIdempotencyKey() {
  return `k6-${__VU}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export default function () {
  group('authorize-capture-refund', () => {
    const idempotencyKey = buildIdempotencyKey();
    const amount = buildAmount();

    const authorizePayload = JSON.stringify({
      merchantId: MERCHANT_ID,
      amount,
      currency: 'KRW',
      idempotencyKey
    });

    const authorizeResponse = http.post(`${BASE_URL}/payments/authorize`, authorizePayload, headers);
    authorizeTrend.add(authorizeResponse.timings.duration);

    const authorizeOk = check(authorizeResponse, {
      'authorize success or duplicate': (res) => res.status === 200 || res.status === 409
    });

    if (!authorizeOk) {
      errorRate.add(1);
      return;
    }

    let paymentId;
    try {
      const payload = authorizeResponse.json();
      paymentId = payload?.paymentId ?? payload?.response?.paymentId;
    } catch (error) {
      errorRate.add(1);
      return;
    }

    if (!paymentId) {
      errorRate.add(1);
      return;
    }

    sleep(0.5);

    const capturePayload = JSON.stringify({
      merchantId: MERCHANT_ID
    });

    const captureResponse = http.post(`${BASE_URL}/payments/capture/${paymentId}`, capturePayload, headers);
    captureTrend.add(captureResponse.timings.duration);

    const captureOk = check(captureResponse, {
      'capture success or conflict': (res) => res.status === 200 || res.status === 409
    });

    if (!captureOk) {
      errorRate.add(1);
    }

    sleep(0.5);

    if (Math.random() < 0.3) {
      const refundPayload = JSON.stringify({
        merchantId: MERCHANT_ID,
        reason: 'k6 refund simulation'
      });

      const refundResponse = http.post(`${BASE_URL}/payments/refund/${paymentId}`, refundPayload, headers);
      refundTrend.add(refundResponse.timings.duration);

      const refundOk = check(refundResponse, {
        'refund success or conflict': (res) => res.status === 200 || res.status === 409
      });

      if (!refundOk) {
        errorRate.add(1);
      }
    }

    sleep(1);
  });
}
