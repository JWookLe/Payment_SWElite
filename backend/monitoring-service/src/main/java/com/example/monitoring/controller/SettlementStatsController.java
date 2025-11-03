package com.example.monitoring.controller;

import com.example.monitoring.service.SettlementStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 정산/환불 통계 API
 */
@RestController
@RequestMapping("/api/stats")
public class SettlementStatsController {

    private final SettlementStatsService statsService;

    public SettlementStatsController(SettlementStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/settlement")
    public Map<String, Object> getSettlementStats() {
        return statsService.getSettlementStats();
    }

    @GetMapping("/refund")
    public Map<String, Object> getRefundStats() {
        return statsService.getRefundStats();
    }

    @GetMapping("/overview")
    public Map<String, Object> getOverview() {
        return statsService.getOverviewStats();
    }
}
