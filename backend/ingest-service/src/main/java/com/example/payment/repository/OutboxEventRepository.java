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
}
