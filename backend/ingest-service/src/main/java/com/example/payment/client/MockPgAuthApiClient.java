package com.example.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock PG Authorization API Client
 * 실제 PG사 카드 승인 API 호출을 시뮬레이션
 */
@Component
public class MockPgAuthApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockPgAuthApiClient.class);

    /**
     * 카드 승인 요청 (Mock)
     * - 1~3초 지연 시뮬레이션 (실제 PG API 응답 시간)
     * - 5% 확률로 실패 시뮬레이션
     *
     * @param merchantId 가맹점 ID
     * @param amount 승인 금액
     * @param currency 통화
     * @param cardNumber 카드 번호 (실제론 마스킹되어 전달)
     * @return 승인 응답
     * @throws PgApiException PG API 오류 발생 시
     */
    public AuthorizationResponse requestAuthorization(
            String merchantId,
            BigDecimal amount,
            String currency,
            String cardNumber
    ) throws PgApiException {
        log.info("Requesting authorization to Mock PG: merchantId={}, amount={}, currency={}",
                merchantId, amount, currency);

        // 1~3초 지연 시뮬레이션 (실제 PG API 응답 시간)
        try {
            int delay = ThreadLocalRandom.current().nextInt(1000, 3000);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PgApiException("PG_INTERRUPTED", "승인 API 호출 중단");
        }

        // 5% 확률로 실패 시뮬레이션
        if (Math.random() < 0.05) {
            log.warn("Mock PG authorization failed (random failure): merchantId={}, amount={}", merchantId, amount);
            throw new PgApiException("PG_TIMEOUT", "승인 API 타임아웃");
        }

        // 성공 응답
        String approvalNumber = "APP" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String transactionId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Mock PG authorization succeeded: merchantId={}, approvalNumber={}, transactionId={}",
                merchantId, approvalNumber, transactionId);

        return new AuthorizationResponse(
                "SUCCESS",
                transactionId,
                approvalNumber,
                "0000",
                "승인 성공",
                amount,
                Instant.now()
        );
    }

    /**
     * 승인 응답 DTO
     */
    public static class AuthorizationResponse {
        private final String status;
        private final String transactionId;
        private final String approvalNumber;
        private final String responseCode;
        private final String responseMessage;
        private final BigDecimal amount;
        private final Instant authorizedAt;

        public AuthorizationResponse(String status, String transactionId, String approvalNumber,
                                      String responseCode, String responseMessage,
                                      BigDecimal amount, Instant authorizedAt) {
            this.status = status;
            this.transactionId = transactionId;
            this.approvalNumber = approvalNumber;
            this.responseCode = responseCode;
            this.responseMessage = responseMessage;
            this.amount = amount;
            this.authorizedAt = authorizedAt;
        }

        public String getStatus() {
            return status;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public String getApprovalNumber() {
            return approvalNumber;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getResponseMessage() {
            return responseMessage;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public Instant getAuthorizedAt() {
            return authorizedAt;
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
