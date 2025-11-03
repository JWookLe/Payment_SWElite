package com.example.refund.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 환불 요청 엔티티
 * PG사 환불 처리 관리
 */
@Entity
@Table(name = "refund_request")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "refund_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private RefundStatus status;

    @Column(name = "pg_cancel_transaction_id", length = 255)
    private String pgCancelTransactionId;

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
    public RefundRequest() {
    }

    public RefundRequest(Long paymentId, BigDecimal refundAmount, String refundReason) {
        this.paymentId = paymentId;
        this.refundAmount = refundAmount;
        this.refundReason = refundReason;
        this.status = RefundStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    // 비즈니스 메서드
    public void markSuccess(String pgCancelTransactionId, String responseCode, String responseMessage) {
        this.status = RefundStatus.SUCCESS;
        this.pgCancelTransactionId = pgCancelTransactionId;
        this.pgResponseCode = responseCode;
        this.pgResponseMessage = responseMessage;
        this.completedAt = Instant.now();
    }

    public void markFailed(String responseCode, String responseMessage) {
        this.status = RefundStatus.FAILED;
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

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public void setStatus(RefundStatus status) {
        this.status = status;
    }

    public String getPgCancelTransactionId() {
        return pgCancelTransactionId;
    }

    public void setPgCancelTransactionId(String pgCancelTransactionId) {
        this.pgCancelTransactionId = pgCancelTransactionId;
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
     * 환불 요청 상태
     */
    public enum RefundStatus {
        PENDING,    // 처리 대기 중
        SUCCESS,    // 환불 성공
        FAILED      // 환불 실패
    }
}
