package com.example.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산/환불 통계 서비스
 */
@Service
public class SettlementStatsService {

    private final JdbcTemplate jdbcTemplate;

    public SettlementStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 정산 통계 조회
     */
    public Map<String, Object> getSettlementStats() {
        Map<String, Object> stats = new HashMap<>();

        // 총 정산 건수
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request",
                Integer.class
        );

        // 정산 성공 건수
        Integer successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request WHERE status = 'SUCCESS'",
                Integer.class
        );

        // 정산 실패 건수
        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request WHERE status = 'FAILED'",
                Integer.class
        );

        // 대기 중인 정산
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request WHERE status = 'PENDING'",
                Integer.class
        );

        // 총 정산 금액
        Double totalAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(request_amount), 0) FROM settlement_request WHERE status = 'SUCCESS'",
                Double.class
        );

        // Dead Letter (최대 재시도 초과)
        Integer deadLetterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request WHERE status = 'FAILED' AND retry_count >= 10",
                Integer.class
        );

        stats.put("totalCount", totalCount);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("pendingCount", pendingCount);
        stats.put("totalAmount", totalAmount);
        stats.put("deadLetterCount", deadLetterCount);
        stats.put("successRate", totalCount > 0 ? (double) successCount / totalCount * 100 : 0.0);

        return stats;
    }

    /**
     * 환불 통계 조회
     */
    public Map<String, Object> getRefundStats() {
        Map<String, Object> stats = new HashMap<>();

        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request",
                Integer.class
        );

        Integer successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE status = 'SUCCESS'",
                Integer.class
        );

        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE status = 'FAILED'",
                Integer.class
        );

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE status = 'PENDING'",
                Integer.class
        );

        Double totalAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM refund_request WHERE status = 'SUCCESS'",
                Double.class
        );

        Integer deadLetterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE status = 'FAILED' AND retry_count >= 10",
                Integer.class
        );

        stats.put("totalCount", totalCount);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("pendingCount", pendingCount);
        stats.put("totalAmount", totalAmount);
        stats.put("deadLetterCount", deadLetterCount);
        stats.put("successRate", totalCount > 0 ? (double) successCount / totalCount * 100 : 0.0);

        return stats;
    }

    /**
     * 전체 통계 조회
     */
    public Map<String, Object> getOverviewStats() {
        Map<String, Object> stats = new HashMap<>();

        // 결제 상태별 통계
        Integer authorizedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'AUTHORIZED'",
                Integer.class
        );

        Integer capturedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'CAPTURED'",
                Integer.class
        );

        Integer refundedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'REFUNDED'",
                Integer.class
        );

        stats.put("authorizedCount", authorizedCount);
        stats.put("capturedCount", capturedCount);
        stats.put("refundedCount", refundedCount);
        stats.put("settlement", getSettlementStats());
        stats.put("refund", getRefundStats());

        return stats;
    }
}
