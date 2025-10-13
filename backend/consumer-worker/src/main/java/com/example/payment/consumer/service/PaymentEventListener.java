package com.example.payment.consumer.service;

import com.example.payment.consumer.domain.LedgerEntry;
import com.example.payment.consumer.repository.LedgerEntryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(LedgerEntryRepository ledgerEntryRepository, ObjectMapper objectMapper) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.authorized", "payment.captured", "payment.refunded"})
    @Transactional
    public void handleEvent(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received event on topic {}: {}", topic, payload);
        try {
            JsonNode node = objectMapper.readTree(payload);
            Long paymentId = node.path("paymentId").asLong();
            long amount = node.path("amount").asLong();
            String occurredAt = node.path("occurredAt").asText(null);

            if ("payment.captured".equals(topic)) {
                if (!ledgerEntryRepository.existsByPaymentIdAndDebitAccountAndCreditAccount(
                        paymentId, "merchant_receivable", "cash")) {
                    ledgerEntryRepository.save(new LedgerEntry(
                            paymentId,
                            "merchant_receivable",
                            "cash",
                            amount,
                            occurredAt != null ? Instant.parse(occurredAt) : Instant.now()
                    ));
                }
            } else if ("payment.refunded".equals(topic)) {
                if (!ledgerEntryRepository.existsByPaymentIdAndDebitAccountAndCreditAccount(
                        paymentId, "cash", "merchant_receivable")) {
                    ledgerEntryRepository.save(new LedgerEntry(
                            paymentId,
                            "cash",
                            "merchant_receivable",
                            amount,
                            occurredAt != null ? Instant.parse(occurredAt) : Instant.now()
                    ));
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse event payload", e);
        }
    }
}
