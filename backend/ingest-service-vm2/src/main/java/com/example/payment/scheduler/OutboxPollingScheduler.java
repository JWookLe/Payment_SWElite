package com.example.payment.scheduler;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.service.PaymentEventPublisher;
import com.example.payment.config.shard.ShardContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Outbox Polling Scheduler - VM2 (Odd Merchant IDs)
 *
 * Handles transactional outbox pattern for odd merchant IDs only.
 * This instance polls and publishes events for merchantId % 2 == 1.
 *
 * Core Responsibilities:
 * 1. Poll unpublished outbox events for odd merchant IDs at regular intervals
 * 2. Batch process events to Kafka with Circuit Breaker protection
 * 3. Handle retries with exponential backoff
 * 4. Prevent duplicate processing with pessimistic locking
 *
 * No ShedLock needed - merchant ID partitioning ensures no overlap with VM1.
 * VM1 handles even IDs, VM2 handles odd IDs → true parallel processing.
 */
@Component
public class OutboxPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingScheduler.class);

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${outbox.polling.batch-size:200}")
    private int batchSize;

    @Value("${outbox.polling.max-retries:10}")
    private int maxRetries;

    @Value("${outbox.polling.retry-interval-seconds:30}")
    private int retryIntervalSeconds;

    @Value("${outbox.polling.enabled:true}")
    private boolean pollingEnabled;

    public OutboxPollingScheduler(OutboxEventRepository outboxEventRepository,
                                  PaymentEventPublisher paymentEventPublisher,
                                  PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.paymentEventPublisher = paymentEventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Main polling loop - executes every 100ms
     * Polls only odd merchant IDs (VM2)
     *
     * Processing flow:
     * 1. Query unpublished events with pessimistic lock (odd IDs only)
     * 2. For each event, submit async Kafka publish via Circuit Breaker
     * 3. Callback will mark as published on success
     * 4. On failure: callback doesn't mark published, next poll will retry
     * 5. Dead letter candidates (max retries exceeded) are logged
     *
     * Note: With async publishing, success/failure counts are approximate
     * since callbacks complete asynchronously after this method returns.
     *
     * Merchant ID Partitioning:
     * - VM1 polls events where merchantId % 2 == 0 (even IDs)
     * - VM2 polls events where merchantId % 2 == 1 (odd IDs)
     * - No ShedLock needed - natural partitioning prevents conflicts
     */
    @Scheduled(
            initialDelayString = "${outbox.polling.initial-delay-ms:1000}",
            fixedDelayString = "${outbox.polling.interval-ms:${outbox.polling.fixed-delay-ms:100}}"
    )
    public void pollAndPublishOutboxEvents() {
        if (!pollingEnabled) {
            log.info("Outbox polling disabled via configuration");
            return;
        }
        // Poll only odd merchant IDs for VM2 (using shard2)
        ShardContextHolder.setShardKey("shard2");
        try {
            log.info("Outbox polling start for odd merchant IDs (VM2)");
            pollAndPublishWithRetry();
        } catch (Exception ex) {
            log.error("Outbox polling cycle failed for odd merchant IDs", ex);
        } finally {
            ShardContextHolder.clear();
        }
    }

    private void pollAndPublishWithRetry() {
        int maxAttempts = 3;
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                Instant retryThreshold = Instant.now().minus(retryIntervalSeconds, ChronoUnit.SECONDS);
                Pageable pageable = PageRequest.of(0, batchSize);

                List<OutboxEvent> events = fetchEventsWithLockOddParity(retryThreshold, pageable);

                if (events == null || events.isEmpty()) {
                    log.debug("Outbox polling found no unpublished events for odd merchant IDs");
                    return;
                }

                log.info("Outbox polling found {} unpublished events for odd merchant IDs", events.size());

                // Submit all events for async publishing (non-blocking)
                for (OutboxEvent event : events) {
                    try {
                        String topic = paymentEventPublisher.resolveTopicName(event.getEventType());
                        paymentEventPublisher.publishToKafkaWithCircuitBreaker(event, topic, event.getPayload(), "shard2");
                    } catch (Exception ex) {
                        log.error("Failed to submit outbox event for publishing id={}, aggregateId={}, eventType={}",
                                event.getId(), event.getAggregateId(), event.getEventType(), ex);
                        // Still update retry count even if submission failed
                        incrementRetryCount(event);
                    }
                }

                log.debug("Submitted {} events for async Kafka publishing", events.size());

                // Check for dead letter candidates periodically
                checkDeadLetterCandidates();

                return; // Success

            } catch (org.springframework.dao.CannotAcquireLockException lockEx) {
                attempt++;
                if (attempt >= maxAttempts) {
                    log.warn("Lock conflict detected {} times, skipping this poll cycle", maxAttempts);
                    return;
                }
                // Add exponential backoff: 10ms, 20ms, 40ms
                try {
                    Thread.sleep(10L * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.debug("Lock conflict on odd merchant IDs, retrying poll (attempt {}/{})", attempt + 1, maxAttempts);
            }
        }
    }

    private List<OutboxEvent> fetchEventsWithLockOddParity(Instant retryThreshold, Pageable pageable) {
        return transactionTemplate.execute(status ->
                outboxEventRepository.findUnpublishedEventsForRetryOddParity(
                        maxRetries, retryThreshold, pageable));
    }

    private void incrementRetryCount(OutboxEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            event.incrementRetryCount();
            outboxEventRepository.save(event);
        });
    }

    /**
     * Monitor events that exceeded max retries
     * These events require manual intervention or DLQ processing
     */
    private void checkDeadLetterCandidates() {
        try {
            List<OutboxEvent> deadLetters = outboxEventRepository.findDeadLetterCandidates(
                    maxRetries, PageRequest.of(0, 10));

            if (!deadLetters.isEmpty()) {
                log.error("Found {} dead letter candidates (exceeded {} retries). Manual intervention required.",
                        deadLetters.size(), maxRetries);
                deadLetters.forEach(event ->
                        log.error("Dead letter event: id={}, aggregateId={}, eventType={}, retryCount={}",
                                event.getId(), event.getAggregateId(), event.getEventType(), event.getRetryCount())
                );
            }
        } catch (Exception ex) {
            log.error("Failed to check dead letter candidates", ex);
        }
    }
}
