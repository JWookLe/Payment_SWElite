package com.example.settlement.consumer;

import com.example.settlement.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 정산 이벤트 Consumer
 * payment.capture-requested 토픽 구독
 */
@Component
public class SettlementEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventConsumer.class);

    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    public SettlementEventConsumer(SettlementService settlementService, ObjectMapper objectMapper) {
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
    }

    /**
     * payment.capture-requested 이벤트 처리
     */
    @KafkaListener(
            topics = "payment.capture-requested",
            groupId = "settlement-worker-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleCaptureRequested(@Payload String message,
                                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                       @Header(KafkaHeaders.OFFSET) Long offset) {
        log.info("Received capture-requested event from topic={}, offset={}", topic, offset);

        try {
            // JSON 파싱
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);

            Long paymentId = getLongValue(payload, "paymentId");
            Long amount = getLongValue(payload, "amount");

            log.info("Processing capture request: paymentId={}, amount={}", paymentId, amount);

            // 정산 처리
            settlementService.processSettlement(paymentId, amount);

        } catch (Exception ex) {
            log.error("Failed to process capture-requested event: {}", ex.getMessage(), ex);
            // DLQ로 전송 또는 재시도 로직 추가
        }
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("Invalid value for key: " + key);
    }
}
