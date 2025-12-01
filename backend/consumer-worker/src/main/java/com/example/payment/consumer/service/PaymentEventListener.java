package com.example.payment.consumer.service;

import com.example.payment.consumer.domain.LedgerEntry;
import com.example.payment.consumer.repository.LedgerEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import static com.example.payment.consumer.config.ShardContextHolder.clear;
import static com.example.payment.consumer.config.ShardContextHolder.setShardKey;

@Service
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;

    public PaymentEventListener(LedgerEntryRepository ledgerEntryRepository,
                                ObjectMapper objectMapper,
                                KafkaTemplate<String, String> kafkaTemplate,
                                @Value("${payment.dlq-topic:payment.dlq}") String dlqTopic) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
    }

    @KafkaListener(
            topics = {"payment.captured", "payment.refunded"},
            concurrency = "${spring.kafka.listener.concurrency:1}"
    )
    @Transactional
    public void handleEvent(ConsumerRecord<String, String> record) {
        String payload = record.value();
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();

        log.info("Received event on topic {} partition {} offset {}: {}", topic, partition, offset, payload);
        try {
            JsonNode node = objectMapper.readTree(payload);
            Long paymentId = node.path("paymentId").asLong();
            long amount = node.path("amount").asLong();
            String occurredAt = node.path("occurredAt").asText(null);

            if ("payment.captured".equals(topic)) {
                saveWithShardFallback(new LedgerEntry(
                        paymentId,
                        "merchant_receivable",
                        "cash",
                        amount,
                        occurredAt != null ? Instant.parse(occurredAt) : Instant.now()
                ));
            } else if ("payment.refunded".equals(topic)) {
                saveWithShardFallback(new LedgerEntry(
                        paymentId,
                        "cash",
                        "merchant_receivable",
                        amount,
                        occurredAt != null ? Instant.parse(occurredAt) : Instant.now()
                ));
            } else {
                log.debug("No ledger action required for topic {}", topic);
            }
        } catch (Exception ex) {
            log.error("Failed to process event from topic {} partition {} offset {}", topic, partition, offset, ex);
            markTransactionForRollback();
            sendToDlq(payload, topic, partition, offset, ex);
        }
    }

    /**
     * Try shard1 first; on FK violation retry shard2. Clears context after use.
     */
    private void saveWithShardFallback(LedgerEntry entry) {
        clear();
        try {
            setShardKey("shard1");
            ledgerEntryRepository.save(entry);
            return;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Shard1 save failed for paymentId={} (retrying shard2): {}", entry.getPaymentId(), ex.getMessage());
            clear();
            try {
                setShardKey("shard2");
                ledgerEntryRepository.save(entry);
                return;
            } catch (DataIntegrityViolationException ex2) {
                log.error("Shard2 save also failed for paymentId={}: {}", entry.getPaymentId(), ex2.getMessage());
                throw ex2;
            }
        } finally {
            clear();
        }
    }

    private void sendToDlq(String payload,
                           String topic,
                           int partition,
                           long offset,
                           Exception ex) {
        Map<String, Object> dlqMessage = new LinkedHashMap<>();
        dlqMessage.put("originalTopic", topic);
        dlqMessage.put("partition", partition);
        dlqMessage.put("offset", offset);
        dlqMessage.put("payload", payload);
        dlqMessage.put("errorType", ex.getClass().getSimpleName());
        dlqMessage.put("errorMessage", ex.getMessage());
        dlqMessage.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());

        try {
            String body = objectMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(dlqTopic, body).get();
        } catch (Exception sendException) {
            log.error("Failed to publish event to DLQ topic {}", dlqTopic, sendException);
            throw new IllegalStateException("DLQ publish failed", sendException);
        }
    }

    private void markTransactionForRollback() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // no transactional context to roll back
        }
    }
}
