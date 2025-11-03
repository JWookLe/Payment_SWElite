package com.example.refund.repository;

import com.example.refund.domain.RefundRequest;
import com.example.refund.domain.RefundRequest.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    /**
     * 재시도 가능한 실패 건 조회
     */
    List<RefundRequest> findByStatusAndRetryCountLessThanAndLastRetryAtBefore(
            RefundStatus status,
            int retryCount,
            Instant lastRetryAt
    );

    /**
     * Dead Letter 조회
     */
    List<RefundRequest> findByStatusAndRetryCountGreaterThanEqual(
            RefundStatus status,
            int retryCount
    );
}
