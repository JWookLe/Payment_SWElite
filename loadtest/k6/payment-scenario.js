import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// Environment variables for scenario control
const ENABLE_CAPTURE = __ENV.ENABLE_CAPTURE === 'true';
const ENABLE_REFUND = __ENV.ENABLE_REFUND === 'true';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Custom metrics
const authorizeSuccess = new Counter('authorize_success');
const authorizeFailed = new Counter('authorize_failed');
const captureSuccess = new Counter('capture_success');
const captureFailed = new Counter('capture_failed');
const refundSuccess = new Counter('refund_success');
const refundFailed = new Counter('refund_failed');

export const options = {
    stages: [
        { duration: '30s', target: 10 },  // Ramp up to 10 VUs
        { duration: '1m', target: 50 },   // Ramp up to 50 VUs
        { duration: '2m', target: 100 },  // Ramp up to 100 VUs
        { duration: '1m', target: 50 },   // Ramp down to 50 VUs
        { duration: '30s', target: 0 },   // Ramp down to 0 VUs
    ],
    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        http_req_failed: ['rate<0.01'],
        authorize_success: ['count>0'],
    },
};

function generateIdempotencyKey() {
    return `test-${Date.now()}-${Math.random().toString(36).substring(2, 11)}`;
}

function generateOrderId() {
    return `order-${Date.now()}-${Math.random().toString(36).substring(2, 11)}`;
}

export default function () {
    const idempotencyKey = generateIdempotencyKey();
    const orderId = generateOrderId();
    const amount = Math.floor(Math.random() * 90000) + 10000;

    // 1. Payment Authorization
    const authorizePayload = JSON.stringify({
        idempotencyKey: idempotencyKey,
        orderId: orderId,
        amount: amount,
        currency: 'KRW',
        paymentMethod: 'CARD',
        cardInfo: {
            cardNumber: '1234-5678-9012-3456',
            expiryMonth: '12',
            expiryYear: '2025',
            cvv: '123',
        },
    });

    const authorizeRes = http.post(
        `${BASE_URL}/payments/authorize`,
        authorizePayload,
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'Authorize' },
        }
    );

    const authorizeOk = check(authorizeRes, {
        'authorize status is 200': (r) => r.status === 200,
        'authorize has paymentId': (r) => JSON.parse(r.body).paymentId !== undefined,
    });

    if (authorizeOk) {
        authorizeSuccess.add(1);
        const authorizeBody = JSON.parse(authorizeRes.body);
        const paymentId = authorizeBody.paymentId;

        sleep(1);

        // 2. Payment Capture (optional)
        if (ENABLE_CAPTURE) {
            const capturePayload = JSON.stringify({
                amount: amount,
            });

            const captureRes = http.post(
                `${BASE_URL}/payments/capture/${paymentId}`,
                capturePayload,
                {
                    headers: { 'Content-Type': 'application/json' },
                    tags: { name: 'Capture' },
                }
            );

            const captureOk = check(captureRes, {
                'capture status is 200': (r) => r.status === 200,
            });

            if (captureOk) {
                captureSuccess.add(1);
            } else {
                captureFailed.add(1);
            }

            sleep(1);

            // 3. Refund (optional)
            if (ENABLE_REFUND) {
                const refundAmount = Math.floor(amount * 0.5);

                const refundPayload = JSON.stringify({
                    amount: refundAmount,
                    reason: 'Load test refund',
                });

                const refundRes = http.post(
                    `${BASE_URL}/payments/refund/${paymentId}`,
                    refundPayload,
                    {
                        headers: { 'Content-Type': 'application/json' },
                        tags: { name: 'Refund' },
                    }
                );

                const refundOk = check(refundRes, {
                    'refund status is 200': (r) => r.status === 200,
                });

                if (refundOk) {
                    refundSuccess.add(1);
                } else {
                    refundFailed.add(1);
                }
            }
        }
    } else {
        authorizeFailed.add(1);
        console.error(`Authorize failed: ${authorizeRes.status} - ${authorizeRes.body}`);
    }

    sleep(1);
}
