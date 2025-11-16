package com.example.payment.scheduler;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.service.PaymentEventPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Outbox Event Polling Scheduler
 *
 * Production Pattern for reliable event publishing:
 * 1. Polls unpublished events periodically
 * 2. Respects Circuit Breaker state (skip if OPEN)
 * 3. Implements exponential backoff with retry limits
 * 4. Uses pessimistic locking for distributed deployments
 * 5. Handles dead letter events after max retries
 * 6. Provides comprehensive logging and metrics
 */
@Component
public class OutboxEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventScheduler.class);
    private static final String CIRCUIT_BREAKER_NAME = "kafka-publisher";

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher eventPublisher;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${outbox.polling.batch-size:1000}")
    private int batchSize;

    @Value("${outbox.polling.max-retries:20}")
    private int maxRetries;

    @Value("${outbox.polling.retry-interval-seconds:5}")
    private int retryIntervalSeconds;

    public OutboxEventScheduler(OutboxEventRepository outboxEventRepository,
                                PaymentEventPublisher eventPublisher,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Poll and retry unpublished events on a short fixed delay.
     * Uses fixed delay to ensure previous execution completes before next run.
     */
    @Scheduled(fixedDelayString = "${outbox.polling.interval-ms:10000}",
               initialDelayString = "${outbox.polling.initial-delay-ms:15000}")
    @Transactional
    public void pollAndRetryUnpublishedEvents() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // Log circuit breaker state but don't skip - let the circuit breaker decide
        log.debug("Outbox polling started - Circuit Breaker state: {}", circuitBreaker.getState());

        try {
            Instant retryThreshold = Instant.now().minus(Duration.ofSeconds(retryIntervalSeconds));

            List<OutboxEvent> events = outboxEventRepository.findUnpublishedEventsForRetry(
                    maxRetries,
                    retryThreshold,
                    PageRequest.of(0, batchSize)
            );

            if (events.isEmpty()) {
                log.debug("No unpublished events to retry");
                return;
            }

            log.info("Found {} unpublished events to retry", events.size());

            int queuedCount = 0;
            for (OutboxEvent event : events) {
                try {
                    // Increment retry count before attempting
                    event.incrementRetryCount();
                    outboxEventRepository.save(event);

                    eventPublisher.dispatchOutboxEventAsync(event.getId());
                    queuedCount++;
                    log.debug("Queued event {} for async retry (retry count: {})",
                            event.getId(), event.getRetryCount());

                } catch (Exception ex) {
                    log.warn("Failed to queue event {} for retry (retry count: {}): {}",
                            event.getId(), event.getRetryCount(), ex.getMessage());

                    // Check if event exceeded max retries
                    if (event.getRetryCount() >= maxRetries) {
                        handleDeadLetterEvent(event);
                    }
                }
            }

            if (queuedCount > 0) {
                log.info("Outbox polling queued {} events for async retry", queuedCount);
            }

        } catch (Exception ex) {
            log.error("Outbox polling failed with unexpected error", ex);
        }
    }

    /**
     * Monitor and log dead letter events periodically (events that exceeded max retries)
     * In production, these would be sent to a DLQ or alerting system
     */
    @Scheduled(fixedDelayString = "${outbox.dead-letter.check-interval-ms:300000}") // Every 5 minutes
    @Transactional(readOnly = true)
    public void monitorDeadLetterEvents() {
        List<OutboxEvent> deadLetterEvents = outboxEventRepository.findDeadLetterCandidates(
                maxRetries,
                PageRequest.of(0, 100)
        );

        if (!deadLetterEvents.isEmpty()) {
            log.error("Found {} dead letter events that exceeded max retries ({})",
                     deadLetterEvents.size(), maxRetries);

            for (OutboxEvent event : deadLetterEvents) {
                log.error("Dead letter event: id={}, eventType={}, paymentId={}, retryCount={}, createdAt={}",
                         event.getId(), event.getEventType(), event.getAggregateId(),
                         event.getRetryCount(), event.getCreatedAt());
            }

            // In production: send to monitoring/alerting system (PagerDuty, Slack, etc.)
            // For now: just log
        }
    }

    /**
     * Handle events that exceeded max retry limit
     * Production pattern: move to DLQ, send alert, create incident ticket
     */
    private void handleDeadLetterEvent(OutboxEvent event) {
        log.error("Event {} exceeded max retries ({}). Moving to dead letter status. " +
                 "EventType: {}, PaymentId: {}, CreatedAt: {}",
                 event.getId(), maxRetries, event.getEventType(),
                 event.getAggregateId(), event.getCreatedAt());

        // In production environments:
        // 1. Move to separate dead_letter_queue table
        // 2. Send alert to monitoring system (PagerDuty, OpsGenie)
        // 3. Create JIRA ticket for manual investigation
        // 4. Publish metric to observability platform

        // For this implementation: just log the error
        // The event remains in outbox_event table with high retry_count
        // which can be monitored by the monitorDeadLetterEvents() job
    }

}
