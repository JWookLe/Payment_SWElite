package com.example.payment.repository;

import com.example.payment.domain.RefundRequest;
import com.example.payment.domain.RefundRequest.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    /**
     * Payment ID로 환불 요청 목록 조회 (부분 환불 지원)
     */
    List<RefundRequest> findByPaymentId(Long paymentId);

    /**
     * 상태별 환불 요청 조회
     */
    List<RefundRequest> findByStatus(RefundStatus status);

    /**
     * 재시도 가능한 실패 건 조회
     */
    @Query("SELECT r FROM RefundRequest r WHERE r.status = 'FAILED' " +
           "AND r.retryCount < :maxRetries " +
           "AND (r.lastRetryAt IS NULL OR r.lastRetryAt < :retryThreshold)")
    List<RefundRequest> findRetryableFailedRequests(
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") Instant retryThreshold
    );

    /**
     * 특정 기간 동안 생성된 환불 요청 조회
     */
    @Query("SELECT r FROM RefundRequest r WHERE r.requestedAt BETWEEN :startDate AND :endDate")
    List<RefundRequest> findByRequestedAtBetween(
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate
    );

    /**
     * 환불 완료된 건 수 조회
     */
    long countByStatus(RefundStatus status);
}
