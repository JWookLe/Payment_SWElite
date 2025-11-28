package com.example.refund.consumer;

import com.example.refund.config.ShardContextHolder;
import com.example.refund.service.RefundService;
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
 * 환불 요청 이벤트 Consumer
 * payment.refund-requested 토픽 구독
 */
@Component
public class RefundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundEventConsumer.class);

    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    public RefundEventConsumer(RefundService refundService, ObjectMapper objectMapper) {
        this.refundService = refundService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "payment.refund-requested",
            groupId = "refund-worker-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleRefundRequested(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("Received refund-requested event from topic={}, offset={}", topic, offset);

        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);

            Long paymentId = getLongValue(payload, "paymentId");
            String merchantId = (String) payload.get("merchantId");
            Long amount = getLongValue(payload, "amount");
            String reason = (String) payload.getOrDefault("reason", "고객 요청");

            log.info("Processing refund request: paymentId={}, merchantId={}, amount={}, reason={}", paymentId, merchantId, amount, reason);

            // 트랜잭션 시작 전에 샤드 컨텍스트 설정 (AbstractRoutingDataSource가 올바른 샤드로 연결)
            ShardContextHolder.setShardByMerchantId(merchantId);
            try {
                refundService.processRefund(paymentId, merchantId, amount, reason);
            } finally {
                ShardContextHolder.clear();
            }

        } catch (Exception ex) {
            log.error("Failed to process refund-requested event: topic={}, offset={}", topic, offset, ex);
            throw new RuntimeException("Refund event processing failed", ex);
        }
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Long) {
            return (Long) value;
        }
        throw new IllegalArgumentException("Invalid value for key: " + key);
    }
}
