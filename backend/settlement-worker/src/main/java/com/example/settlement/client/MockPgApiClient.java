package com.example.settlement.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock PG API Client
 * 실제 PG사 API 호출을 시뮬레이션
 */
@Component
public class MockPgApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockPgApiClient.class);

    @org.springframework.beans.factory.annotation.Value("${mock.pg.loadtest-mode:false}")
    private boolean loadTestMode;

    /**
     * 정산 요청 (매입 확정)
     * 실제 PG API 호출 시뮬레이션
     *
     * @param paymentId 결제 ID
     * @param amount 정산 금액
     * @return 정산 응답
     * @throws PgApiException PG API 오류 발생 시
     */
    public SettlementResponse requestSettlement(Long paymentId, BigDecimal amount) throws PgApiException {
        log.info("Requesting settlement to Mock PG: paymentId={}, amount={}", paymentId, amount);

        // 1~3초 지연 시뮬레이션 (실제 PG API 응답 시간)
        try {
            int delay = ThreadLocalRandom.current().nextInt(1000, 3000);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PgApiException("PG_INTERRUPTED", "정산 API 호출 중단");
        }

        // 실패 시뮬레이션
        // 부하테스트 모드: 거의 성공 (0.01% 실패) - 성능 측정용
        // 일반 모드: 현실적인 실패율 (5% 실패) - 에러 처리 검증용
        double failureRate = loadTestMode ? 0.0001 : 0.05;
        if (Math.random() < failureRate) {
            log.warn("Mock PG settlement failed (random failure): paymentId={}, mode={}",
                    paymentId, loadTestMode ? "LOADTEST" : "NORMAL");
            throw new PgApiException("PG_TIMEOUT", "정산 API 타임아웃");
        }

        // 성공 응답
        String transactionId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Mock PG settlement succeeded: paymentId={}, transactionId={}", paymentId, transactionId);

        return new SettlementResponse(
                "SUCCESS",
                transactionId,
                "0000",
                "정산 성공",
                amount,
                Instant.now()
        );
    }

    /**
     * 정산 응답 DTO
     */
    public static class SettlementResponse {
        private final String status;
        private final String transactionId;
        private final String responseCode;
        private final String responseMessage;
        private final BigDecimal amount;
        private final Instant capturedAt;

        public SettlementResponse(String status, String transactionId, String responseCode,
                                  String responseMessage, BigDecimal amount, Instant capturedAt) {
            this.status = status;
            this.transactionId = transactionId;
            this.responseCode = responseCode;
            this.responseMessage = responseMessage;
            this.amount = amount;
            this.capturedAt = capturedAt;
        }

        public String getStatus() {
            return status;
        }

        public String getTransactionId() {
            return transactionId;
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

        public Instant getCapturedAt() {
            return capturedAt;
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
