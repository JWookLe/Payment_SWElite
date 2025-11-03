package com.example.refund.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock PG API 클라이언트
 * 실제 PG사 환불 API 호출을 시뮬레이션
 */
@Component
public class MockPgApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockPgApiClient.class);

    /**
     * 환불 요청 (Mock)
     * - 1~3초 지연 시뮬레이션
     * - 5% 확률로 실패 시뮬레이션
     */
    public RefundResponse requestRefund(Long paymentId, BigDecimal amount, String reason) throws PgApiException {
        try {
            // 1~3초 지연 시뮬레이션
            int delay = ThreadLocalRandom.current().nextInt(1000, 3000);
            log.info("Requesting refund to Mock PG: paymentId={}, amount={}, reason={}", paymentId, amount, reason);
            Thread.sleep(delay);

            // 5% 확률로 실패 시뮬레이션
            if (Math.random() < 0.05) {
                throw new PgApiException("PG_TIMEOUT", "환불 API 타임아웃");
            }

            String cancelTransactionId = "cancel_" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Mock PG refund succeeded: paymentId={}, cancelTransactionId={}", paymentId, cancelTransactionId);

            return new RefundResponse(
                    "SUCCESS",
                    cancelTransactionId,
                    "0000",
                    "환불 성공",
                    amount,
                    Instant.now()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PgApiException("INTERRUPTED", "환불 API 중단됨");
        }
    }

    /**
     * 환불 응답 DTO
     */
    public static class RefundResponse {
        private final String status;
        private final String cancelTransactionId;
        private final String responseCode;
        private final String responseMessage;
        private final BigDecimal refundedAmount;
        private final Instant refundedAt;

        public RefundResponse(String status, String cancelTransactionId, String responseCode,
                              String responseMessage, BigDecimal refundedAmount, Instant refundedAt) {
            this.status = status;
            this.cancelTransactionId = cancelTransactionId;
            this.responseCode = responseCode;
            this.responseMessage = responseMessage;
            this.refundedAmount = refundedAmount;
            this.refundedAt = refundedAt;
        }

        public String getStatus() {
            return status;
        }

        public String getCancelTransactionId() {
            return cancelTransactionId;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getResponseMessage() {
            return responseMessage;
        }

        public BigDecimal getRefundedAmount() {
            return refundedAmount;
        }

        public Instant getRefundedAt() {
            return refundedAt;
        }
    }

    /**
     * PG API 예외
     */
    public static class PgApiException extends Exception {
        private final String errorCode;

        public PgApiException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
