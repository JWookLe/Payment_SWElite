package com.example.settlement.controller;

import com.example.settlement.domain.SettlementRequest;
import com.example.settlement.repository.SettlementRequestRepository;
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
 * Settlement 관리 API
 */
@RestController
@RequestMapping("/admin/settlement")
public class SettlementAdminController {

    private static final Logger log = LoggerFactory.getLogger(SettlementAdminController.class);

    private final SettlementRequestRepository settlementRequestRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SettlementAdminController(SettlementRequestRepository settlementRequestRepository,
                                     KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper) {
        this.settlementRequestRepository = settlementRequestRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * DB의 Dead Letter를 Kafka로 마이그레이션
     */
    @PostMapping("/migrate-dlq")
    public Map<String, Object> migrateDlqToKafka() {
        try {
            List<SettlementRequest> deadLetters = settlementRequestRepository
                    .findByStatusAndRetryCountGreaterThanEqual(
                            SettlementRequest.SettlementStatus.FAILED,
                            10
                    );

            int successCount = 0;
            int failCount = 0;

            for (SettlementRequest request : deadLetters) {
                try {
                    Map<String, Object> dlqMessage = new HashMap<>();
                    dlqMessage.put("settlementRequestId", request.getId());
                    dlqMessage.put("paymentId", request.getPaymentId());
                    dlqMessage.put("requestAmount", request.getRequestAmount());
                    dlqMessage.put("retryCount", request.getRetryCount());
                    dlqMessage.put("status", request.getStatus().name());
                    dlqMessage.put("pgResponseCode", request.getPgResponseCode());
                    dlqMessage.put("pgResponseMessage", request.getPgResponseMessage());
                    dlqMessage.put("errorMessage", "Migrated from database");
                    dlqMessage.put("requestedAt", request.getRequestedAt().toString());
                    dlqMessage.put("lastRetryAt", request.getLastRetryAt() != null ? request.getLastRetryAt().toString() : null);
                    dlqMessage.put("timestamp", Instant.now().toString());

                    String jsonMessage = objectMapper.writeValueAsString(dlqMessage);
                    kafkaTemplate.send("settlement.dlq", String.valueOf(request.getPaymentId()), jsonMessage);

                    successCount++;
                    log.info("Migrated settlement DLQ to Kafka: paymentId={}", request.getPaymentId());
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to migrate settlement DLQ: paymentId={}, error={}",
                            request.getPaymentId(), e.getMessage());
                }
            }

            return Map.of(
                    "success", true,
                    "totalCount", deadLetters.size(),
                    "successCount", successCount,
                    "failCount", failCount,
                    "message", "Settlement DLQ migration completed"
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "Failed to migrate settlement DLQ: " + e.getMessage()
            );
        }
    }
}
