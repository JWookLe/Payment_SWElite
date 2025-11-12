package com.example.monitoring.controller;

import com.example.monitoring.service.AdminTestService;
import com.example.monitoring.dto.TestReportDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 운영 관리자 대시보드 API
 * 테스트 실행 및 AI 보고서 생성 기능 제공
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminTestController {

    private final AdminTestService adminTestService;

    @Autowired
    public AdminTestController(AdminTestService adminTestService) {
        this.adminTestService = adminTestService;
    }

    /**
     * K6 부하 테스트 - 승인 전용
     */
    @PostMapping("/tests/k6/authorize-only")
    public ResponseEntity<TestReportDTO> runK6AuthorizeOnlyTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runK6Test("authorize-only", testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * K6 부하 테스트 - 전체 플로우
     */
    @PostMapping("/tests/k6/full-flow")
    public ResponseEntity<TestReportDTO> runK6FullFlowTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runK6Test("full-flow", testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Circuit Breaker 테스트
     */
    @PostMapping("/tests/circuit-breaker")
    public ResponseEntity<TestReportDTO> runCircuitBreakerTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runCircuitBreakerTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Health Check 테스트
     */
    @PostMapping("/tests/health-check")
    public ResponseEntity<TestReportDTO> runHealthCheckTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runHealthCheckTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Database 통계 테스트
     */
    @PostMapping("/tests/database-stats")
    public ResponseEntity<TestReportDTO> runDatabaseStatsTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runDatabaseStatsTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Redis 통계 테스트
     */
    @PostMapping("/tests/redis-stats")
    public ResponseEntity<TestReportDTO> runRedisStatsTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runRedisStatsTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Kafka 통계 테스트
     */
    @PostMapping("/tests/kafka-stats")
    public ResponseEntity<TestReportDTO> runKafkaStatsTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runKafkaStatsTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * Settlement 통계 테스트
     */
    @PostMapping("/tests/settlement-stats")
    public ResponseEntity<TestReportDTO> runSettlementStatsTest(@RequestBody Map<String, Object> request) {
        boolean generateReport = (boolean) request.getOrDefault("generateReport", true);
        String testId = (String) request.get("testId");

        TestReportDTO report = adminTestService.runSettlementStatsTest(testId, generateReport);
        return ResponseEntity.ok(report);
    }

    /**
     * 최근 보고서 조회
     */
    @GetMapping("/reports/recent")
    public ResponseEntity<List<TestReportDTO>> getRecentReports(
            @RequestParam(defaultValue = "20") int limit) {
        List<TestReportDTO> reports = adminTestService.getRecentReports(limit);
        return ResponseEntity.ok(reports);
    }

    /**
     * 특정 테스트의 히스토리 조회
     */
    @GetMapping("/reports/history/{testId}")
    public ResponseEntity<List<TestReportDTO>> getTestHistory(
            @PathVariable String testId,
            @RequestParam(defaultValue = "10") int limit) {
        List<TestReportDTO> reports = adminTestService.getTestHistory(testId, limit);
        return ResponseEntity.ok(reports);
    }

    /**
     * 보고서 상세 조회
     */
    @GetMapping("/reports/{reportId}")
    public ResponseEntity<TestReportDTO> getReport(@PathVariable String reportId) {
        TestReportDTO report = adminTestService.getReport(reportId);
        return ResponseEntity.ok(report);
    }

    /**
     * 테스트 상태 조회 (진행 중인 테스트 또는 완료된 테스트)
     */
    @GetMapping("/tests/status/{testId}")
    public ResponseEntity<TestReportDTO> getTestStatus(@PathVariable String testId) {
        TestReportDTO report = adminTestService.getTestStatus(testId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }
}
