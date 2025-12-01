package com.example.payment.scheduler;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.service.PaymentEventPublisher;
import com.example.payment.config.shard.ShardContextHolder;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
import java.util.Arrays;
import java.util.List;

/**
 * Outbox Polling Scheduler - Production-grade Transactional Outbox Pattern
 *
 * Core Responsibilities:
 * 1. Poll unpublished outbox events at regular intervals
 * 2. Batch process events to Kafka with Circuit Breaker protection
 * 3. Handle retries with exponential backoff
 * 4. Prevent duplicate processing with pessimistic locking
 *
 * Scalability:
 * - Processes up to 1000 events per second (1000 batch size / 1 second interval)
 * - Horizontal scaling supported via distributed locking (future: ShedLock)
 * - Independent from HTTP request processing (fault isolation)
 *
 * Circuit Breaker Integration:
 * - Uses PaymentEventPublisher's Circuit Breaker for Kafka failures
 * - When CB is OPEN, events remain in outbox for next retry
 * - Automatic recovery when CB transitions to HALF_OPEN -> CLOSED
 */
@Component
public class OutboxPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingScheduler.class);
    private static final List<String> SHARDS = Arrays.asList("shard1", "shard2");

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
     *
     * Processing flow:
     * 1. Query unpublished events with pessimistic lock (prevents race conditions)
     * 2. For each event, submit async Kafka publish via Circuit Breaker
     * 3. Callback will mark as published on success
     * 4. On failure: callback doesn't mark published, next poll will retry
     * 5. Dead letter candidates (max retries exceeded) are logged
     *
     * Note: With async publishing, success/failure counts are approximate
     * since callbacks complete asynchronously after this method returns.
     *
     * ShedLock Integration:
     * - Only one instance (VM) acquires the lock and executes this task
     * - Prevents duplicate processing and eliminates deadlock contention
     * - Lock is held for the duration of the task execution
     */
    @Scheduled(
            initialDelayString = "${outbox.polling.initial-delay-ms:1000}",
            fixedDelayString = "${outbox.polling.interval-ms:${outbox.polling.fixed-delay-ms:100}}"
    )
    @SchedulerLock(name = "pollAndPublishOutboxEvents",
            lockAtMostFor = "30s",
            lockAtLeastFor = "1s")
    public void pollAndPublishOutboxEvents() {
        if (!pollingEnabled) {
            log.info("Outbox polling disabled via configuration");
            return;
        }
        // shard2 먼저, shard1 순서로 폴링해 백로그를 우선 처리
        pollShard("shard2");
        pollShard("shard1");
    }

    private void pollShard(String shard) {
        // ingest-service는 샤드별로 개별 데이터소스를 사용하므로 명시적으로 샤드 컨텍스트를 설정
        ShardContextHolder.setShardKey(shard);
        try {
            log.info("Outbox polling start for shard {}", shard);
            pollAndPublishWithRetry(shard);
        } catch (Exception ex) {
            log.error("Outbox polling cycle failed for shard {}", shard, ex);
        } finally {
            ShardContextHolder.clear();
        }
    }

    private void pollAndPublishWithRetry(String shard) {
        int maxAttempts = 3;
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                Instant retryThreshold = Instant.now().minus(retryIntervalSeconds, ChronoUnit.SECONDS);
                Pageable pageable = PageRequest.of(0, batchSize);

                List<OutboxEvent> events = fetchEventsWithLock(retryThreshold, pageable);

                if (events == null || events.isEmpty()) {
                    log.info("Outbox polling ({}) found no unpublished events", shard);
                    return;
                }

                log.info("Outbox polling ({}) found {} unpublished events", shard, events.size());

                // Submit all events for async publishing (non-blocking)
                for (OutboxEvent event : events) {
                    try {
                        String topic = paymentEventPublisher.resolveTopicName(event.getEventType());
                        paymentEventPublisher.publishToKafkaWithCircuitBreaker(event, topic, event.getPayload(), shard);
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
                    log.warn("Deadlock detected {} times, skipping this poll cycle", maxAttempts);
                    return;
                }
                // Add exponential backoff: 10ms, 20ms, 40ms
                try {
                    Thread.sleep(10L * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.debug("Deadlock detected on shard {}, retrying poll (attempt {}/{})", shard, attempt + 1, maxAttempts);
            }
        }
    }

    private List<OutboxEvent> fetchEventsWithLock(Instant retryThreshold, Pageable pageable) {
        return transactionTemplate.execute(status ->
                outboxEventRepository.findUnpublishedEventsForRetry(
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
