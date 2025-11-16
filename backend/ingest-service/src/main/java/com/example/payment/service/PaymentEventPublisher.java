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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TaskExecutor outboxDispatchExecutor;
    private final TransactionTemplate transactionTemplate;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                OutboxEventRepository outboxEventRepository,
                                ObjectMapper objectMapper,
                                CircuitBreakerRegistry circuitBreakerRegistry,
                                @Qualifier("outboxDispatchExecutor") TaskExecutor outboxDispatchExecutor,
                                TransactionTemplate transactionTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.outboxDispatchExecutor = outboxDispatchExecutor;
        this.transactionTemplate = transactionTemplate;

        // Register event listeners for monitoring circuit breaker state changes
        registerCircuitBreakerEventListeners(circuitBreakerRegistry);
    }

    /**
     * Publishes a payment event to Kafka with Circuit Breaker protection.
     *
     * @param paymentId The payment aggregate identifier
     * @param eventType The type of payment event (PAYMENT_AUTHORIZED, PAYMENT_CAPTURED, etc.)
     * @param payload The event payload as a map
     */
    public void publishEvent(Long paymentId, String eventType, Map<String, Object> payload) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);

            // Save to outbox first (always persisted)
            OutboxEvent outboxEvent = outboxEventRepository.save(
                    new OutboxEvent("payment", paymentId, eventType, jsonPayload));

            // Dispatch asynchronously after the transaction commits so HTTP threads are not blocked
            scheduleAsyncDispatch(outboxEvent.getId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for paymentId={}, eventType={}",
                    paymentId, eventType, e);
        }
    }

    /**
     * Allows other components (e.g. outbox scheduler) to trigger asynchronous dispatch for an event id.
     */
    public void dispatchOutboxEventAsync(Long outboxEventId) {
        outboxDispatchExecutor.execute(() -> processOutboxEvent(outboxEventId));
    }

    /**
     * Publishes to Kafka with circuit breaker protection using programmatic approach.
     * If circuit is OPEN, falls back to keeping the event in outbox (will be retried later).
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
                // Synchronous send with timeout to allow Circuit Breaker to catch exceptions
                kafkaTemplate.send(message).get(3, java.util.concurrent.TimeUnit.SECONDS);
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

    private void processOutboxEvent(Long outboxEventId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    outboxEventRepository.findById(outboxEventId).ifPresent(event -> {
                        if (event.isPublished()) {
                            return;
                        }
                        publishToKafkaWithCircuitBreaker(event, resolveTopicName(event.getEventType()), event.getPayload());
                    })
            );
        } catch (Exception ex) {
            log.warn("Async dispatch failed for eventId={}. It will be retried by the scheduler if necessary.",
                    outboxEventId, ex);
        }
    }

    private void scheduleAsyncDispatch(Long outboxEventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchOutboxEventAsync(outboxEventId);
                }
            });
        } else {
            dispatchOutboxEventAsync(outboxEventId);
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
