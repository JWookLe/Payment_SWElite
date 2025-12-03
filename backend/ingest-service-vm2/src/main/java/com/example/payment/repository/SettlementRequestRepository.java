package com.example.payment.repository;

import com.example.payment.domain.SettlementRequest;
import com.example.payment.domain.SettlementRequest.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRequestRepository extends JpaRepository<SettlementRequest, Long> {

    /**
     * Payment ID로 정산 요청 조회
     */
    Optional<SettlementRequest> findByPaymentId(Long paymentId);

    /**
     * 상태별 정산 요청 조회
     */
    List<SettlementRequest> findByStatus(SettlementStatus status);

    /**
     * 재시도 가능한 실패 건 조회
     */
    @Query("SELECT s FROM SettlementRequest s WHERE s.status = 'FAILED' " +
           "AND s.retryCount < :maxRetries " +
           "AND (s.lastRetryAt IS NULL OR s.lastRetryAt < :retryThreshold)")
    List<SettlementRequest> findRetryableFailedRequests(
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") Instant retryThreshold
    );

    /**
     * 특정 기간 동안 생성된 정산 요청 조회
     */
    @Query("SELECT s FROM SettlementRequest s WHERE s.requestedAt BETWEEN :startDate AND :endDate")
    List<SettlementRequest> findByRequestedAtBetween(
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate
    );

    /**
     * 정산 완료된 건 수 조회
     */
    long countByStatus(SettlementStatus status);
}
