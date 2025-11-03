package com.example.payment.service;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Publishes payment events to Kafka with Circuit Breaker protection.
 *
 * Responsibilities:
 * - Publishes events to Kafka topics
 * - Detects and handles Kafka failures
 * - Falls back to DLQ (Direct to Database) when Kafka is unavailable
 * - Provides metrics and monitoring via Resilience4j
 *
 * Circuit Breaker Behavior:
 * - CLOSED: Normal operation, all requests go to Kafka
 * - OPEN: Kafka is down, all requests are rejected immediately (fail-fast)
 * - HALF_OPEN: Recovery attempt, 3 test requests allowed
 */
@Service
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String CIRCUIT_BREAKER_NAME = "kafka-publisher";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                OutboxEventRepository outboxEventRepository,
                                ObjectMapper objectMapper,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.circuitBreakerRegistry = circuitBreakerRegistry;

        // Register event listeners for monitoring circuit breaker state changes
        registerCircuitBreakerEventListeners(circuitBreakerRegistry);
    }

    /**
     * Publishes a payment event to Kafka with Circuit Breaker protection.
     *
     * @param payment The payment domain object
     * @param eventType The type of payment event (PAYMENT_AUTHORIZED, PAYMENT_CAPTURED, etc.)
     * @param payload The event payload as a map
     */
    public void publishEvent(Long paymentId, String eventType, Map<String, Object> payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);

            // Save to outbox first (always persisted)
            OutboxEvent outboxEvent = outboxEventRepository.save(
                    new OutboxEvent("payment", paymentId, eventType, jsonPayload));

            // Attempt to publish to Kafka (protected by circuit breaker)
            try {
                publishToKafkaWithCircuitBreaker(outboxEvent, topicNameFor(eventType), jsonPayload);
            } catch (Exception ex) {
                // Circuit breaker caught the exception, log and continue
                log.warn("Circuit breaker prevented Kafka publish for paymentId={}, eventType={}", paymentId, eventType, ex);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for paymentId={}, eventType={}",
                    paymentId, eventType, e);
        }
    }

    /**
     * Publishes to Kafka with circuit breaker protection using programmatic approach.
     * If circuit is OPEN, falls back to keeping the event in outbox (will be retried later).
     */
    public void publishToKafkaWithCircuitBreaker(OutboxEvent outboxEvent, String topic, String payload) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        Runnable publishTask = () -> {
            Message<String> message = MessageBuilder
                    .withPayload(payload)
                    .setHeader(KafkaHeaders.TOPIC, topic)
                    .setHeader("eventId", String.valueOf(outboxEvent.getId()))
                    .build();

            try {
                // Synchronous send to allow Circuit Breaker to catch exceptions
                kafkaTemplate.send(message).get();
                log.info("Event published to Kafka topic={}, eventId={}, paymentId={}",
                        topic, outboxEvent.getId(), outboxEvent.getAggregateId());
                // Mark as published in outbox
                outboxEvent.markPublished();
                outboxEventRepository.save(outboxEvent);
            } catch (Exception ex) {
                log.error("Kafka publish failed for topic={}, eventId={}", topic, outboxEvent.getId(), ex);
                throw new KafkaPublishingException("Failed to publish to Kafka", ex);
            }
        };

        try {
            circuitBreaker.executeRunnable(publishTask);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
            // Circuit breaker is OPEN, request was rejected
            log.warn("Circuit Breaker OPEN - request rejected for topic={}, eventId={}. Event will be retried by outbox polling.",
                    topic, outboxEvent.getId());
            // Event is already saved in outbox, so we don't throw
        } catch (Exception ex) {
            // Other errors (timeout, etc)
            log.warn("Circuit Breaker caught error for topic={}, eventId={}. Error: {}",
                    topic, outboxEvent.getId(), ex.getMessage());
            // Event is already saved in outbox, so we don't throw
        }
    }


    /**
     * Registers event listeners to log circuit breaker state transitions.
     * Useful for debugging and monitoring.
     */
    private void registerCircuitBreakerEventListeners(CircuitBreakerRegistry circuitBreakerRegistry) {
        var circuitBreaker = circuitBreakerRegistry.find(CIRCUIT_BREAKER_NAME).orElse(null);

        if (circuitBreaker != null) {
            circuitBreaker.getEventPublisher()
                    .onStateTransition(event ->
                            log.warn("Circuit Breaker state transition: {} -> {}",
                                    event.getStateTransition().getFromState(),
                                    event.getStateTransition().getToState()))
                    .onError(event ->
                            log.error("Circuit Breaker error recorded: {}",
                                    event.getThrowable().getMessage()))
                    .onSuccess(event ->
                            log.debug("Circuit Breaker success recorded with {} total calls",
                                    circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()));
        }
    }

    private String topicNameFor(String eventType) {
        return switch (eventType) {
            case "PAYMENT_AUTHORIZED" -> "payment.authorized";
            case "PAYMENT_CAPTURE_REQUESTED" -> "payment.capture-requested";
            case "PAYMENT_CAPTURED" -> "payment.captured";
            case "PAYMENT_REFUND_REQUESTED" -> "payment.refund-requested";
            case "PAYMENT_REFUNDED" -> "payment.refunded";
            default -> "payment.unknown";
        };
    }

    /**
     * Custom exception for Kafka publishing failures.
     * Used to distinguish from other types of failures.
     */
    public static class KafkaPublishingException extends RuntimeException {
        public KafkaPublishingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
