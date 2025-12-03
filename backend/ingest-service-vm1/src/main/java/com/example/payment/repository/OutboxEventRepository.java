package com.example.payment.repository;

import com.example.payment.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();

    /**
     * Find unpublished events that can be retried with pessimistic lock
     * to prevent duplicate processing in distributed environments
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
           "AND e.retryCount < :maxRetries " +
           "AND (e.lastRetryAt IS NULL OR e.lastRetryAt < :retryThreshold) " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedEventsForRetry(
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") Instant retryThreshold,
            Pageable pageable
    );

    /**
     * Find events that exceeded max retries (dead letter candidates)
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
           "AND e.retryCount >= :maxRetries " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findDeadLetterCandidates(
            @Param("maxRetries") int maxRetries,
            Pageable pageable
    );

    /**
     * Find unpublished events for even merchant IDs only (VM1)
     * Pessimistic lock prevents race conditions in distributed environment
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
           "AND e.retryCount < :maxRetries " +
           "AND (e.lastRetryAt IS NULL OR e.lastRetryAt < :retryThreshold) " +
           "AND MOD(CAST(SUBSTRING(e.aggregateId, 1) AS INTEGER), 2) = 0 " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedEventsForRetryEvenParity(
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") Instant retryThreshold,
            Pageable pageable
    );

    /**
     * Find unpublished events for odd merchant IDs only (VM2)
     * Pessimistic lock prevents race conditions in distributed environment
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false " +
           "AND e.retryCount < :maxRetries " +
           "AND (e.lastRetryAt IS NULL OR e.lastRetryAt < :retryThreshold) " +
           "AND MOD(CAST(SUBSTRING(e.aggregateId, 1) AS INTEGER), 2) = 1 " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedEventsForRetryOddParity(
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") Instant retryThreshold,
            Pageable pageable
    );
}
