package com.example.refund.controller;

import com.example.refund.domain.RefundRequest;
import com.example.refund.repository.RefundRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Refund 관리 API
 */
@RestController
@RequestMapping("/admin/refund")
public class RefundAdminController {

    private static final Logger log = LoggerFactory.getLogger(RefundAdminController.class);

    private final RefundRequestRepository refundRequestRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RefundAdminController(RefundRequestRepository refundRequestRepository,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.refundRequestRepository = refundRequestRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * DB의 Dead Letter를 Kafka로 마이그레이션
     */
    @PostMapping("/migrate-dlq")
    public Map<String, Object> migrateDlqToKafka() {
        try {
            List<RefundRequest> deadLetters = refundRequestRepository
                    .findByStatusAndRetryCountGreaterThanEqual(
                            RefundRequest.RefundStatus.FAILED,
                            10
                    );

            int successCount = 0;
            int failCount = 0;

            for (RefundRequest request : deadLetters) {
                try {
                    Map<String, Object> dlqMessage = new HashMap<>();
                    dlqMessage.put("refundRequestId", request.getId());
                    dlqMessage.put("paymentId", request.getPaymentId());
                    dlqMessage.put("refundAmount", request.getRefundAmount());
                    dlqMessage.put("refundReason", request.getRefundReason());
                    dlqMessage.put("retryCount", request.getRetryCount());
                    dlqMessage.put("status", request.getStatus().name());
                    dlqMessage.put("pgResponseCode", request.getPgResponseCode());
                    dlqMessage.put("pgResponseMessage", request.getPgResponseMessage());
                    dlqMessage.put("errorMessage", "Migrated from database");
                    dlqMessage.put("requestedAt", request.getRequestedAt().toString());
                    dlqMessage.put("lastRetryAt", request.getLastRetryAt() != null ? request.getLastRetryAt().toString() : null);
                    dlqMessage.put("timestamp", Instant.now().toString());

                    String jsonMessage = objectMapper.writeValueAsString(dlqMessage);
                    kafkaTemplate.send("refund.dlq", String.valueOf(request.getPaymentId()), jsonMessage);

                    successCount++;
                    log.info("Migrated refund DLQ to Kafka: paymentId={}", request.getPaymentId());
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to migrate refund DLQ: paymentId={}, error={}",
                            request.getPaymentId(), e.getMessage());
                }
            }

            return Map.of(
                    "success", true,
                    "totalCount", deadLetters.size(),
                    "successCount", successCount,
                    "failCount", failCount,
                    "message", "Refund DLQ migration completed"
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "Failed to migrate refund DLQ: " + e.getMessage()
            );
        }
    }
}
