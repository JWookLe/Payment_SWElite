package com.example.monitoring.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for Database monitoring and queries
 * Provides same functionality as database-query-mcp but via HTTP
 */
@RestController
@RequestMapping("/monitoring/database")
public class DatabaseMonitoringController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * GET /monitoring/database/payments?filter=...&limit=10
     * Query payments with natural language filters
     */
    @GetMapping("/payments")
    public ResponseEntity<Map<String, Object>> queryPayments(
            @RequestParam String filter,
            @RequestParam(defaultValue = "10") int limit) {

        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT payment_id, merchant_id, amount, currency, status, " +
                    "idempotency_key, requested_at, updated_at " +
                    "FROM payment WHERE 1=1");

            List<Object> params = new ArrayList<>();
            parseFilter(filter, sql, params);

            sql.append(" ORDER BY requested_at DESC LIMIT ?");
            params.add(limit);

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            return ResponseEntity.ok(Map.of(
                    "count", results.size(),
                    "filter", filter,
                    "payments", results
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Query failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/database/statistics?timeRange=today
     * Get payment statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(defaultValue = "today") String timeRange) {

        try {
            String whereClause = getTimeRangeClause(timeRange);

            // Overall stats
            String statsSql = "SELECT COUNT(*) as total_count, " +
                    "COALESCE(SUM(amount), 0) as total_amount, " +
                    "COALESCE(AVG(amount), 0) as avg_amount, " +
                    "COALESCE(MIN(amount), 0) as min_amount, " +
                    "COALESCE(MAX(amount), 0) as max_amount " +
                    "FROM payment WHERE " + whereClause;

            Map<String, Object> stats = jdbcTemplate.queryForMap(statsSql);

            // By status
            String statusSql = "SELECT status, COUNT(*) as count, " +
                    "COALESCE(SUM(amount), 0) as total_amount " +
                    "FROM payment WHERE " + whereClause +
                    " GROUP BY status";

            List<Map<String, Object>> statusBreakdown = jdbcTemplate.queryForList(statusSql);

            // Top merchants
            String merchantSql = "SELECT merchant_id, COUNT(*) as transaction_count, " +
                    "COALESCE(SUM(amount), 0) as total_amount " +
                    "FROM payment WHERE " + whereClause +
                    " GROUP BY merchant_id ORDER BY total_amount DESC LIMIT 5";

            List<Map<String, Object>> topMerchants = jdbcTemplate.queryForList(merchantSql);

            return ResponseEntity.ok(Map.of(
                    "timeRange", timeRange,
                    "overall", stats,
                    "byStatus", statusBreakdown,
                    "topMerchants", topMerchants
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Statistics query failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/database/outbox?maxAgeMinutes=5
     * Check for stuck outbox events
     */
    @GetMapping("/outbox")
    public ResponseEntity<Map<String, Object>> checkOutbox(
            @RequestParam(defaultValue = "5") int maxAgeMinutes) {

        try {
            String sql = "SELECT event_id, aggregate_type, aggregate_id, event_type, " +
                    "published, created_at, " +
                    "TIMESTAMPDIFF(MINUTE, created_at, NOW()) as age_minutes " +
                    "FROM outbox_event " +
                    "WHERE published = 0 AND created_at < DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
                    "ORDER BY created_at ASC";

            List<Map<String, Object>> stuckEvents = jdbcTemplate.queryForList(sql, maxAgeMinutes);

            if (stuckEvents.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "healthy", true,
                        "message", "No stuck events found",
                        "events", Collections.emptyList()
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "healthy", false,
                    "message", stuckEvents.size() + " unpublished events older than " + maxAgeMinutes + " minutes",
                    "count", stuckEvents.size(),
                    "events", stuckEvents
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Outbox check failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/database/reconciliation
     * Verify double-entry bookkeeping integrity
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<Map<String, Object>> checkReconciliation() {
        try {
            String balanceSql = "SELECT COALESCE(SUM(amount), 0) as total FROM ledger_entry";
            Long total = jdbcTemplate.queryForObject(balanceSql, Long.class);

            String debitSql = "SELECT debit_account, COALESCE(SUM(amount), 0) as total " +
                    "FROM ledger_entry GROUP BY debit_account";
            List<Map<String, Object>> debits = jdbcTemplate.queryForList(debitSql);

            String creditSql = "SELECT credit_account, COALESCE(SUM(amount), 0) as total " +
                    "FROM ledger_entry GROUP BY credit_account";
            List<Map<String, Object>> credits = jdbcTemplate.queryForList(creditSql);

            long debitSum = debits.stream()
                    .mapToLong(m -> ((Number) m.get("total")).longValue())
                    .sum();

            long creditSum = credits.stream()
                    .mapToLong(m -> ((Number) m.get("total")).longValue())
                    .sum();

            boolean balanced = debitSum == creditSum;

            return ResponseEntity.ok(Map.of(
                    "balanced", balanced,
                    "totalDebits", debitSum,
                    "totalCredits", creditSum,
                    "debitAccounts", debits,
                    "creditAccounts", credits,
                    "message", balanced ? "Books are balanced" : "IMBALANCE DETECTED!"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Reconciliation check failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/database/ledger?paymentId=123
     * Get ledger entries for a payment
     */
    @GetMapping("/ledger")
    public ResponseEntity<Map<String, Object>> getLedgerEntries(
            @RequestParam(required = false) Long paymentId,
            @RequestParam(required = false) String merchantId) {

        try {
            String sql;
            Object[] params;

            if (paymentId != null) {
                sql = "SELECT entry_id, payment_id, debit_account, credit_account, " +
                        "amount, occurred_at FROM ledger_entry " +
                        "WHERE payment_id = ? ORDER BY occurred_at DESC";
                params = new Object[]{paymentId};
            } else if (merchantId != null) {
                sql = "SELECT le.entry_id, le.payment_id, le.debit_account, le.credit_account, " +
                        "le.amount, le.occurred_at FROM ledger_entry le " +
                        "JOIN payment p ON le.payment_id = p.payment_id " +
                        "WHERE p.merchant_id = ? ORDER BY le.occurred_at DESC LIMIT 20";
                params = new Object[]{merchantId};
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Either paymentId or merchantId is required"
                ));
            }

            List<Map<String, Object>> entries = jdbcTemplate.queryForList(sql, params);

            return ResponseEntity.ok(Map.of(
                    "count", entries.size(),
                    "entries", entries
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Ledger query failed",
                    "message", e.getMessage()
            ));
        }
    }

    private void parseFilter(String filter, StringBuilder sql, List<Object> params) {
        String filterLower = filter.toLowerCase();

        // Status filter
        if (filterLower.contains("failed") || filterLower.contains("cancelled")) {
            sql.append(" AND status = 'CANCELLED'");
        } else if (filterLower.contains("refund")) {
            sql.append(" AND status = 'REFUNDED'");
        } else if (filterLower.contains("completed")) {
            sql.append(" AND status = 'COMPLETED'");
        } else if (filterLower.contains("requested")) {
            sql.append(" AND status = 'REQUESTED'");
        }

        // Merchant filter
        if (filter.matches(".*merchant[:\\s]+(\\S+).*")) {
            String merchantId = filter.replaceAll(".*merchant[:\\s]+(\\S+).*", "$1");
            sql.append(" AND merchant_id = ?");
            params.add(merchantId);
        }

        // Time filter
        if (filterLower.contains("last hour") || filterLower.contains("1h")) {
            sql.append(" AND requested_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)");
        } else if (filterLower.contains("today")) {
            sql.append(" AND DATE(requested_at) = CURDATE()");
        } else if (filterLower.contains("last 24h") || filterLower.contains("24 hours")) {
            sql.append(" AND requested_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)");
        }

        // Amount filter
        if (filter.matches(".*(?:over|above|>)\\s*(\\d+).*")) {
            String amount = filter.replaceAll(".*(?:over|above|>)\\s*(\\d+).*", "$1");
            sql.append(" AND amount > ?");
            params.add(Long.parseLong(amount));
        }
    }

    private String getTimeRangeClause(String timeRange) {
        switch (timeRange.toLowerCase()) {
            case "today":
                return "DATE(requested_at) = CURDATE()";
            case "last_hour":
                return "requested_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)";
            case "last_24h":
                return "requested_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)";
            case "all":
            default:
                return "1=1";
        }
    }
}
