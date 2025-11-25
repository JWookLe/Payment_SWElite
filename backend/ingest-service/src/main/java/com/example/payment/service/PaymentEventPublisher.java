package com.example.payment.service;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
     * Publishes to Kafka with circuit breaker protection (Asynchronous).
     * Called by OutboxPollingScheduler for background event processing.
     * If circuit is OPEN, falls back to keeping the event in outbox (will be retried later).
     *
     * Uses asynchronous callback to avoid blocking scheduler threads.
     * This allows the scheduler to process more events concurrently.
     */
    public void publishToKafkaWithCircuitBreaker(OutboxEvent outboxEvent, String topic, String payload) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        Runnable publishTask = () -> {
            String messageKey = String.valueOf(outboxEvent.getAggregateId());

            Message<String> message = MessageBuilder
                    .withPayload(payload)
                    .setHeader(KafkaHeaders.TOPIC, topic)
                    .setHeader(KafkaHeaders.KEY, messageKey)
                    .setHeader("eventId", String.valueOf(outboxEvent.getId()))
                    .build();

            try {
                // Asynchronous send with callback - doesn't block scheduler thread
                CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                    kafkaTemplate.send(message);

                future.whenComplete((result, ex) -> {
                    if (ex == null) {
                        // Success
                        log.info("Event published to Kafka topic={}, eventId={}, paymentId={}, partition={}, offset={}",
                                topic, outboxEvent.getId(), outboxEvent.getAggregateId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                        // Mark as published in outbox
                        try {
                            outboxEvent.markPublished();
                            outboxEventRepository.save(outboxEvent);
                        } catch (Exception saveEx) {
                            log.error("Failed to mark event as published: eventId={}, paymentId={}",
                                    outboxEvent.getId(), outboxEvent.getAggregateId(), saveEx);
                        }
                    } else {
                        // Failure
                        log.error("Kafka publish failed for topic={}, eventId={}, paymentId={}. Event remains in outbox for retry.",
                                topic, outboxEvent.getId(), outboxEvent.getAggregateId(), ex);
                        // Event stays in outbox table for retry by scheduler
                    }
                });
            } catch (Exception ex) {
                log.error("Kafka send error for topic={}, eventId={}, paymentId={}", topic, outboxEvent.getId(),
                        outboxEvent.getAggregateId(), ex);
                // Event stays in outbox table for retry by scheduler
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
