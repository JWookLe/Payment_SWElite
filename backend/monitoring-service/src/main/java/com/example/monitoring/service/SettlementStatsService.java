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

        // 지연 정산 감지 (1시간 이상 PENDING 상태)
        Integer delayedSettlements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_request WHERE status = 'PENDING' AND requested_at < DATE_SUB(NOW(), INTERVAL 1 HOUR)",
                Integer.class
        );

        // 실패 정산 상세 (재시도 횟수별)
        List<Map<String, Object>> failureDetails = jdbcTemplate.queryForList(
                "SELECT retry_count, COUNT(*) as count FROM settlement_request WHERE status = 'FAILED' GROUP BY retry_count ORDER BY retry_count DESC"
        );

        stats.put("totalCount", totalCount);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("pendingCount", pendingCount);
        stats.put("totalAmount", totalAmount);
        stats.put("deadLetterCount", deadLetterCount);
        stats.put("delayedSettlements", delayedSettlements);
        stats.put("failureDetails", failureDetails);
        stats.put("successRate", totalCount > 0 ? (double) successCount / totalCount * 100 : 0.0);
        stats.put("failureRate", totalCount > 0 ? (double) failedCount / totalCount * 100 : 0.0);
        stats.put("healthStatus", delayedSettlements > 0 || deadLetterCount > 0 ? "WARNING" : "HEALTHY");

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

        // 지연 환불 감지 (30분 이상 PENDING 상태)
        Integer delayedRefunds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE status = 'PENDING' AND requested_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE)",
                Integer.class
        );

        // 실패 환불 상세 (재시도 횟수별)
        List<Map<String, Object>> failureDetails = jdbcTemplate.queryForList(
                "SELECT retry_count, COUNT(*) as count FROM refund_request WHERE status = 'FAILED' GROUP BY retry_count ORDER BY retry_count DESC"
        );

        stats.put("totalCount", totalCount);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("pendingCount", pendingCount);
        stats.put("totalAmount", totalAmount);
        stats.put("deadLetterCount", deadLetterCount);
        stats.put("delayedRefunds", delayedRefunds);
        stats.put("failureDetails", failureDetails);
        stats.put("successRate", totalCount > 0 ? (double) successCount / totalCount * 100 : 0.0);
        stats.put("failureRate", totalCount > 0 ? (double) failedCount / totalCount * 100 : 0.0);
        stats.put("healthStatus", delayedRefunds > 0 || deadLetterCount > 0 ? "WARNING" : "HEALTHY");

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
