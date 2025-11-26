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
 * Publishes payment events to Kafka using Transactional Outbox Pattern.
 *
 * Architecture:
 * - HTTP requests save events to outbox table only (fast path)
 * - OutboxPollingScheduler publishes to Kafka asynchronously (background)
 * - Circuit Breaker protects Kafka publishing
 *
 * Responsibilities:
 * - Save events to outbox (called by HTTP request handlers)
 * - Publish events to Kafka (called by OutboxPollingScheduler)
 * - Circuit Breaker integration for fault tolerance
 *
 * Circuit Breaker Behavior:
 * - CLOSED: Normal operation, scheduler publishes to Kafka
 * - OPEN: Kafka is down, events remain in outbox for retry
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
     * Publishes a payment event using Transactional Outbox Pattern.
     *
     * HTTP Request Flow (Fast Path):
     * 1. Save event to outbox table (DB transaction)
     * 2. Return HTTP 200 immediately (< 50ms)
     *
     * Background Processing (Polling Scheduler):
     * - OutboxPollingScheduler polls unpublished events every 1 second
     * - Publishes to Kafka with Circuit Breaker protection
     * - Handles retries with exponential backoff
     *
     * Benefits:
     * - HTTP response time independent of Kafka latency
     * - Kafka failures don't affect user experience
     * - Guaranteed eventual delivery via polling
     *
     * @param paymentId The payment aggregate identifier
     * @param eventType The type of payment event (PAYMENT_AUTHORIZED, PAYMENT_CAPTURED, etc.)
     * @param payload The event payload as a map
     */
    public void publishEvent(Long paymentId, String eventType, Map<String, Object> payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);

            // Save to outbox only - HTTP request completes immediately
            // OutboxPollingScheduler will publish to Kafka asynchronously
            outboxEventRepository.save(
                    new OutboxEvent("payment", paymentId, eventType, jsonPayload));

            log.debug("Event saved to outbox: paymentId={}, eventType={}", paymentId, eventType);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for paymentId={}, eventType={}",
                    paymentId, eventType, e);
        }
    }

    /**
     * Publishes to Kafka with circuit breaker protection (Truly Asynchronous).
     * Called by OutboxPollingScheduler for background event processing.
     * If circuit is OPEN, falls back to keeping the event in outbox (will be retried later).
     *
     * Uses non-blocking async callback to avoid blocking scheduler threads.
     * This allows the scheduler to process many events concurrently without waiting.
     */
    public void publishToKafkaWithCircuitBreaker(OutboxEvent outboxEvent, String topic, String payload) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // Check if circuit is OPEN before sending
        if (circuitBreaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
            log.warn("Circuit Breaker OPEN - skipping publish for topic={}, eventId={}. Event will be retried by outbox polling.",
                    topic, outboxEvent.getId());
            // Backoff when circuit is open to avoid tight polling loops
            outboxEvent.incrementRetryCount();
            outboxEventRepository.save(outboxEvent);
            return;
        }

        String messageKey = String.valueOf(outboxEvent.getAggregateId());

        Message<String> message = MessageBuilder
                .withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, messageKey)
                .setHeader("eventId", String.valueOf(outboxEvent.getId()))
                .build();

        try {
            // Non-blocking async send - returns immediately, result handled in callback
            kafkaTemplate.send(message).whenComplete((sendResult, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish failed for topic={}, eventId={}", topic, outboxEvent.getId(), ex);
                    // Record failure for Circuit Breaker metrics
                    try {
                        circuitBreaker.executeRunnable(() -> {
                            throw new KafkaPublishingException("Kafka send failed", ex);
                        });
                    } catch (Exception ignored) {
                        // Event stays in outbox for retry
                    }
                    // Apply retry backoff to prevent hammering DB/Kafka when failures continue
                    outboxEvent.incrementRetryCount();
                    outboxEventRepository.save(outboxEvent);
                } else {
                    log.debug("Event published to Kafka topic={}, eventId={}, paymentId={}",
                            topic, outboxEvent.getId(), outboxEvent.getAggregateId());

                    // Record success for Circuit Breaker metrics
                    try {
                        circuitBreaker.executeRunnable(() -> {
                            // Success - do nothing, just record the success
                        });
                    } catch (Exception ignored) {
                        // Should not happen for success case
                    }

                    // Mark as published in outbox
                    outboxEvent.markPublished();
                    outboxEventRepository.save(outboxEvent);
                }
            });
        } catch (Exception ex) {
            // Error during send submission (not Kafka issue, but send itself)
            log.warn("Error sending to Kafka for topic={}, eventId={}. Error: {}",
                    topic, outboxEvent.getId(), ex.getMessage());
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

    public String resolveTopicName(String eventType) {
        return topicNameFor(eventType);
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
