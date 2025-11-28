package com.example.refund.service;

import com.example.refund.client.MockPgApiClient;
import com.example.refund.config.ShardContextHolder;
import com.example.refund.domain.Payment;
import com.example.refund.domain.PaymentStatus;
import com.example.refund.domain.RefundRequest;
import com.example.refund.repository.PaymentRepository;
import com.example.refund.repository.RefundRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 환불 처리 서비스
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository paymentRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final MockPgApiClient pgApiClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RefundService(PaymentRepository paymentRepository,
                         RefundRequestRepository refundRequestRepository,
                         MockPgApiClient pgApiClient,
                         KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.pgApiClient = pgApiClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processRefund(Long paymentId, String merchantId, Long amount, String reason) {
        log.info("Processing refund: paymentId={}, merchantId={}, shard={}, amount={}, reason={}", paymentId, merchantId, ShardContextHolder.getShardKey(), amount, reason);

        try {
            // Payment 조회 (ShardContextHolder는 Consumer에서 이미 설정됨)
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} already marked as REFUNDED. Re-publishing event if necessary.", paymentId);
            publishRefundedEvent(payment, amount);
            return;
        }

        if (payment.getStatus() != PaymentStatus.REFUND_REQUESTED) {
            log.warn("Received refund request for payment {} in unexpected status {}. Skipping.", paymentId, payment.getStatus());
            return;
        }

        if (refundRequestRepository.existsByPaymentIdAndStatus(paymentId, RefundRequest.RefundStatus.SUCCESS)) {
            log.info("Refund request already completed for payment {}. Ensuring event propagation.", paymentId);
            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);
            publishRefundedEvent(payment, amount);
            return;
        }

        // RefundRequest 생성 및 저장
        RefundRequest refundRequest = new RefundRequest(
                paymentId,
                BigDecimal.valueOf(amount),
                reason
        );
        refundRequestRepository.save(refundRequest);

        try {
            // Mock PG API 호출 (환불 처리)
            MockPgApiClient.RefundResponse response = pgApiClient.requestRefund(
                    paymentId,
                    BigDecimal.valueOf(amount),
                    reason
            );

            // 성공 처리
            refundRequest.markSuccess(
                    response.getCancelTransactionId(),
                    response.getResponseCode(),
                    response.getResponseMessage()
            );
            payment.setStatus(PaymentStatus.REFUNDED);

            paymentRepository.save(payment);
            refundRequestRepository.save(refundRequest);

            // payment.refunded 이벤트 발행
            publishRefundedEvent(payment, amount);

            log.info("Refund succeeded: paymentId={}, cancelTxnId={}", paymentId, response.getCancelTransactionId());

        } catch (MockPgApiClient.PgApiException ex) {
            // 실패 처리
            refundRequest.markFailed(ex.getErrorCode(), ex.getMessage());
            payment.setStatus(PaymentStatus.REFUND_FAILED);

            paymentRepository.save(payment);
            refundRequestRepository.save(refundRequest);

            log.error("Refund failed: paymentId={}, error={}", paymentId, ex.getMessage());
        }
        } finally {
            ShardContextHolder.clear();
        }
    }

    private void publishRefundedEvent(Payment payment, Long amount) {
        try {
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("paymentId", payment.getId());
            eventPayload.put("status", PaymentStatus.REFUNDED.name());
            eventPayload.put("amount", amount);
            eventPayload.put("occurredAt", Instant.now().toString());

            String message = objectMapper.writeValueAsString(eventPayload);
            kafkaTemplate.send("payment.refunded", String.valueOf(payment.getId()), message).get();

            log.info("Published payment.refunded event: paymentId={}", payment.getId());

        } catch (Exception ex) {
            log.error("Failed to publish payment.refunded event: paymentId={}", payment.getId(), ex);
            throw new IllegalStateException("Failed to publish payment.refunded event", ex);
        }
    }
}
