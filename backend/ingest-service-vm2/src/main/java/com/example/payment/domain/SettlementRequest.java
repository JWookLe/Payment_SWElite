package com.example.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 정산 요청 엔티티
 * PG사 매입 확정 처리 관리
 */
@Entity
@Table(name = "settlement_request")
public class SettlementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "request_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal requestAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private SettlementStatus status;

    @Column(name = "pg_transaction_id", length = 255)
    private String pgTransactionId;

    @Column(name = "pg_response_code", length = 50)
    private String pgResponseCode;

    @Column(name = "pg_response_message", columnDefinition = "TEXT")
    private String pgResponseMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // 생성자
    public SettlementRequest() {
    }

    public SettlementRequest(Long paymentId, BigDecimal requestAmount) {
        this.paymentId = paymentId;
        this.requestAmount = requestAmount;
        this.status = SettlementStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    // 비즈니스 메서드
    public void markSuccess(String pgTransactionId, String responseCode, String responseMessage) {
        this.status = SettlementStatus.SUCCESS;
        this.pgTransactionId = pgTransactionId;
        this.pgResponseCode = responseCode;
        this.pgResponseMessage = responseMessage;
        this.completedAt = Instant.now();
    }

    public void markFailed(String responseCode, String responseMessage) {
        this.status = SettlementStatus.FAILED;
        this.pgResponseCode = responseCode;
        this.pgResponseMessage = responseMessage;
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.lastRetryAt = Instant.now();
    }

    public boolean canRetry(int maxRetries) {
        return retryCount < maxRetries;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public BigDecimal getRequestAmount() {
        return requestAmount;
    }

    public void setRequestAmount(BigDecimal requestAmount) {
        this.requestAmount = requestAmount;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public void setStatus(SettlementStatus status) {
        this.status = status;
    }

    public String getPgTransactionId() {
        return pgTransactionId;
    }

    public void setPgTransactionId(String pgTransactionId) {
        this.pgTransactionId = pgTransactionId;
    }

    public String getPgResponseCode() {
        return pgResponseCode;
    }

    public void setPgResponseCode(String pgResponseCode) {
        this.pgResponseCode = pgResponseCode;
    }

    public String getPgResponseMessage() {
        return pgResponseMessage;
    }

    public void setPgResponseMessage(String pgResponseMessage) {
        this.pgResponseMessage = pgResponseMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getLastRetryAt() {
        return lastRetryAt;
    }

    public void setLastRetryAt(Instant lastRetryAt) {
        this.lastRetryAt = lastRetryAt;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    /**
     * 정산 요청 상태
     */
    public enum SettlementStatus {
        PENDING,    // 처리 대기 중
        SUCCESS,    // 정산 성공
        FAILED      // 정산 실패
    }
}
