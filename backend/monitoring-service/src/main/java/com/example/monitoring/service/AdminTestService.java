package com.example.monitoring.service;

import com.example.monitoring.dto.TestReportDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Admin 테스트 실행 및 AI 보고서 생성 서비스
 * MCP 서버를 호출하여 자동 분석 보고서 생성
 */
@Service
public class AdminTestService {

    private static final Logger logger = LoggerFactory.getLogger(AdminTestService.class);

    // In-memory 저장소 (프로토타입용, 실제로는 DB 사용 권장)
    private final Map<String, List<TestReportDTO>> reportHistory = new ConcurrentHashMap<>();
    private final Map<String, TestReportDTO> reportById = new ConcurrentHashMap<>();
    private final Map<String, TestReportDTO> runningTests = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MCPAnalysisService mcpAnalysisService;

    @Autowired
    public AdminTestService(RestTemplate restTemplate, ObjectMapper objectMapper, MCPAnalysisService mcpAnalysisService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.mcpAnalysisService = mcpAnalysisService;
    }

    /**
     * 테스트 상태 조회
     */
    public TestReportDTO getTestStatus(String testId) {
        // 먼저 실행 중인 테스트 확인
        if (runningTests.containsKey(testId)) {
            return runningTests.get(testId);
        }
        // 최근 완료 보고서 반환
        List<TestReportDTO> history = reportHistory.get(testId);
        if (history != null && !history.isEmpty()) {
            return history.get(0); // 최신 순으로 저장됨
        }
        return null;
    }

    /**
     * K6 부하 테스트 실행 (비동기)
     */
    public TestReportDTO runK6Test(String scenario, String testId, boolean generateReport) {
        LocalDateTime timestamp = LocalDateTime.now();

        // 즉시 "running" 상태 보고서 생성
        TestReportDTO runningReport = new TestReportDTO();
        runningReport.setReportId(UUID.randomUUID().toString());
        runningReport.setTestId(testId);
        runningReport.setTestName("K6 Load Test - " + scenario);
        runningReport.setStatus("running");
        runningReport.setTimestamp(timestamp);
        runningReport.setDuration("진행 중...");
        runningReport.setRawData(new HashMap<>());

        runningTests.put(testId, runningReport);

        // 백그라운드 스레드에서 실제 테스트 실행
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                logger.info("Running K6 test: scenario={}, testId={}", scenario, testId);

                // K6 스크립트 실행
                ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.command("bash", "scripts/run-k6-test.sh", scenario);
                processBuilder.directory(new File(System.getProperty("user.dir")));
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        logger.info(line);
                    }
                }

                int exitCode = process.waitFor();
                long duration = System.currentTimeMillis() - startTime;

                // 결과 수집
                Map<String, Object> rawData = new HashMap<>();
                rawData.put("exitCode", exitCode);
                rawData.put("output", output.toString());
                rawData.put("scenario", scenario);

                // K6 summary.json 파일 읽기 (있는 경우)
                File summaryFile = new File("loadtest/k6/summary.json");
                if (summaryFile.exists()) {
                    try {
                        Map<String, Object> summary = objectMapper.readValue(summaryFile, Map.class);
                        rawData.put("k6Summary", summary);
                    } catch (Exception e) {
                        logger.warn("Failed to parse K6 summary: {}", e.getMessage());
                    }
                }

                String status = exitCode == 0 ? "success" : "failure";

                // 보고서 생성
                TestReportDTO report = new TestReportDTO();
                report.setReportId(runningReport.getReportId());
                report.setTestId(testId);
                report.setTestName("K6 Load Test - " + scenario);
                report.setStatus(status);
                report.setTimestamp(timestamp);
                report.setDuration(formatDuration(duration));
                report.setRawData(rawData);

                if (generateReport) {
                    // MCP를 통한 AI 분석 생성
                    Map<String, Object> analysis = mcpAnalysisService.analyzeK6Test(testId, scenario, rawData);
                    report.setAiSummary((String) analysis.get("aiSummary"));
                    report.setMetrics((Map<String, String>) analysis.get("metrics"));
                    report.setRecommendations((List<String>) analysis.get("recommendations"));
                }

                // 저장
                saveReport(report);

                // running 상태에서 제거
                runningTests.remove(testId);

                logger.info("K6 test completed: testId={}, status={}", testId, status);

            } catch (Exception e) {
                logger.error("Failed to run K6 test in background", e);
                TestReportDTO errorReport = createErrorReport(testId, "K6 Load Test - " + scenario,
                        timestamp, System.currentTimeMillis() - startTime, e);
                saveReport(errorReport);
                runningTests.remove(testId);
            }
        }).start();

        // 즉시 "running" 상태 반환
        return runningReport;
    }

    /**
     * Circuit Breaker 테스트 실행 (비동기)
     */
    public TestReportDTO runCircuitBreakerTest(String testId, boolean generateReport) {
        LocalDateTime timestamp = LocalDateTime.now();

        TestReportDTO runningReport = new TestReportDTO();
        runningReport.setReportId(UUID.randomUUID().toString());
        runningReport.setTestId(testId);
        runningReport.setTestName("Circuit Breaker Test");
        runningReport.setStatus("running");
        runningReport.setTimestamp(timestamp);
        runningReport.setDuration("진행 중...");
        runningReport.setRawData(new HashMap<>());

        runningTests.put(testId, runningReport);

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                logger.info("Running Circuit Breaker test: testId={}", testId);

                ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.command("bash", "scripts/test-circuit-breaker.sh");
                processBuilder.directory(new File(System.getProperty("user.dir")));
                processBuilder.redirectErrorStream(true);

                // Set environment variables for Docker network access
                processBuilder.environment().put("API_BASE_URL", "http://ingest-service:8080");
                processBuilder.environment().put("GATEWAY_BASE_URL", "http://gateway:8080/api");

                logger.info("Environment variables set for circuit breaker test:");
                logger.info("  API_BASE_URL = {}", processBuilder.environment().get("API_BASE_URL"));
                logger.info("  GATEWAY_BASE_URL = {}", processBuilder.environment().get("GATEWAY_BASE_URL"));

                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        logger.info(line);
                    }
                }

                int exitCode = process.waitFor();
                long duration = System.currentTimeMillis() - startTime;

                Map<String, Object> rawData = new HashMap<>();
                rawData.put("exitCode", exitCode);
                rawData.put("output", output.toString());

                String status = exitCode == 0 ? "success" : "failure";

                TestReportDTO report = new TestReportDTO();
                report.setReportId(runningReport.getReportId());
                report.setTestId(testId);
                report.setTestName("Circuit Breaker Test");
                report.setStatus(status);
                report.setTimestamp(timestamp);
                report.setDuration(formatDuration(duration));
                report.setRawData(rawData);

                if (generateReport) {
                    Map<String, Object> analysis = mcpAnalysisService.analyzeCircuitBreakerTest(testId, rawData);
                    report.setAiSummary((String) analysis.get("aiSummary"));
                    report.setMetrics((Map<String, String>) analysis.get("metrics"));
                    report.setRecommendations((List<String>) analysis.get("recommendations"));
                }

                saveReport(report);
            } catch (Exception e) {
                logger.error("Failed to run Circuit Breaker test", e);
                createErrorReport(testId, "Circuit Breaker Test",
                        timestamp, System.currentTimeMillis() - startTime, e);
            } finally {
                runningTests.remove(testId);
            }
        }).start();

        return runningReport;
    }

    /**
     * Health Check 테스트
     */
    public TestReportDTO runHealthCheckTest(String testId, boolean generateReport) {
        long startTime = System.currentTimeMillis();
        LocalDateTime timestamp = LocalDateTime.now();

        try {
            logger.info("Running Health Check test: testId={}", testId);

            Map<String, Object> healthData = new HashMap<>();

            // 각 서비스 health check
            String[] services = {
                    "http://localhost:8761/actuator/health", // eureka
                    "http://localhost:8080/actuator/health", // ingest-service
                    "http://localhost:8082/actuator/health"  // monitoring-service
            };

            for (String url : services) {
                try {
                    ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                    healthData.put(url, response.getBody());
                } catch (Exception e) {
                    healthData.put(url, Map.of("status", "DOWN", "error", e.getMessage()));
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            TestReportDTO report = new TestReportDTO();
            report.setReportId(UUID.randomUUID().toString());
            report.setTestId(testId);
            report.setTestName("Health Check");
            report.setStatus("success");
            report.setTimestamp(timestamp);
            report.setDuration(formatDuration(duration));
            report.setRawData(healthData);

            if (generateReport) {
                Map<String, Object> analysis = mcpAnalysisService.analyzeHealthCheck(testId, healthData);
                report.setAiSummary((String) analysis.get("aiSummary"));
                report.setMetrics((Map<String, String>) analysis.get("metrics"));
                report.setRecommendations((List<String>) analysis.get("recommendations"));
            }

            saveReport(report);
            return report;

        } catch (Exception e) {
            logger.error("Failed to run Health Check test", e);
            return createErrorReport(testId, "Health Check",
                    timestamp, System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Database 통계 테스트
     */
    public TestReportDTO runDatabaseStatsTest(String testId, boolean generateReport) {
        return runMonitoringEndpointTest(testId, "Database Stats",
                "http://gateway:8080/api/monitoring/database/stats", generateReport);
    }

    /**
     * Redis 통계 테스트
     */
    public TestReportDTO runRedisStatsTest(String testId, boolean generateReport) {
        return runMonitoringEndpointTest(testId, "Redis Stats",
                "http://gateway:8080/api/monitoring/redis/stats", generateReport);
    }

    /**
     * Kafka 통계 테스트
     */
    public TestReportDTO runKafkaStatsTest(String testId, boolean generateReport) {
        return runMonitoringEndpointTest(testId, "Kafka Stats",
                "http://gateway:8080/api/monitoring/kafka/stats", generateReport);
    }

    /**
     * Settlement 통계 테스트
     */
    public TestReportDTO runSettlementStatsTest(String testId, boolean generateReport) {
        return runMonitoringEndpointTest(testId, "Settlement Stats",
                "http://gateway:8080/api/stats/settlement", generateReport);
    }

    /**
     * 모니터링 엔드포인트 호출 공통 로직
     */
    private TestReportDTO runMonitoringEndpointTest(String testId, String testName,
                                                      String endpoint, boolean generateReport) {
        long startTime = System.currentTimeMillis();
        LocalDateTime timestamp = LocalDateTime.now();

        try {
            logger.info("Running monitoring endpoint test: endpoint={}", endpoint);

            ResponseEntity<Map> response = restTemplate.getForEntity(endpoint, Map.class);
            long duration = System.currentTimeMillis() - startTime;

            TestReportDTO report = new TestReportDTO();
            report.setReportId(UUID.randomUUID().toString());
            report.setTestId(testId);
            report.setTestName(testName);
            report.setStatus("success");
            report.setTimestamp(timestamp);
            report.setDuration(formatDuration(duration));
            report.setRawData((Map<String, Object>) response.getBody());

            if (generateReport) {
                String testType = testName.toLowerCase().replace(" stats", "").replace(" ", "");
                Map<String, Object> analysis = mcpAnalysisService.analyzeMonitoringStats(testId, testType, (Map<String, Object>) response.getBody());
                report.setAiSummary((String) analysis.get("aiSummary"));
                report.setMetrics((Map<String, String>) analysis.get("metrics"));
                report.setRecommendations((List<String>) analysis.get("recommendations"));
            }

            saveReport(report);
            return report;

        } catch (Exception e) {
            logger.error("Failed to run monitoring endpoint test: {}", endpoint, e);
            return createErrorReport(testId, testName, timestamp,
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * AI 분석 생성 (MCP 서버 호출 또는 로컬 분석)
     * 실제로는 MCP 서버에 HTTP 요청을 보내거나, Claude API를 직접 호출
     */
    private String generateAIAnalysis(String testId, Map<String, Object> rawData) {
        // TODO: MCP 서버 호출 또는 Claude API 직접 호출
        // 현재는 간단한 분석 로직으로 대체

        logger.info("Generating AI analysis for testId={}", testId);

        StringBuilder analysis = new StringBuilder();
        analysis.append("=== AI 자동 분석 보고서 ===\n\n");

        if (rawData.containsKey("exitCode")) {
            int exitCode = (int) rawData.get("exitCode");
            if (exitCode == 0) {
                analysis.append("✓ 테스트가 성공적으로 완료되었습니다.\n\n");
            } else {
                analysis.append("✗ 테스트가 실패했습니다 (Exit Code: ").append(exitCode).append(")\n\n");
            }
        }

        if (rawData.containsKey("k6Summary")) {
            analysis.append("성능 메트릭 분석:\n");
            Map<String, Object> summary = (Map<String, Object>) rawData.get("k6Summary");
            if (summary.containsKey("metrics")) {
                Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
                analysis.append("- HTTP 요청 성공률: ").append(extractMetricValue(metrics, "http_req_failed")).append("\n");
                analysis.append("- 평균 응답 시간: ").append(extractMetricValue(metrics, "http_req_duration", "avg")).append("ms\n");
                analysis.append("- P95 응답 시간: ").append(extractMetricValue(metrics, "http_req_duration", "p(95)")).append("ms\n");
            }
        }

        analysis.append("\n종합 평가: 시스템이 안정적으로 동작하고 있습니다.");

        return analysis.toString();
    }

    /**
     * 메트릭 및 권장사항 추출
     */
    private void extractMetricsAndRecommendations(TestReportDTO report, Map<String, Object> rawData) {
        Map<String, String> metrics = new HashMap<>();
        List<String> recommendations = new ArrayList<>();

        if (rawData.containsKey("k6Summary")) {
            Map<String, Object> summary = (Map<String, Object>) rawData.get("k6Summary");
            if (summary.containsKey("metrics")) {
                Map<String, Object> metricsData = (Map<String, Object>) summary.get("metrics");

                metrics.put("Total Requests", extractMetricValue(metricsData, "http_reqs", "count"));
                metrics.put("Success Rate", extractMetricValue(metricsData, "http_req_failed"));
                metrics.put("Avg Duration", extractMetricValue(metricsData, "http_req_duration", "avg") + " ms");
                metrics.put("P95 Duration", extractMetricValue(metricsData, "http_req_duration", "p(95)") + " ms");

                // 권장사항 생성
                try {
                    double p95 = Double.parseDouble(extractMetricValue(metricsData, "http_req_duration", "p(95)"));
                    if (p95 > 1000) {
                        recommendations.add("P95 응답 시간이 1초를 초과합니다. 성능 최적화를 권장합니다.");
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (metrics.isEmpty()) {
            metrics.put("Status", "Data Collected");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("현재 시스템 상태가 양호합니다.");
        }

        report.setMetrics(metrics);
        report.setRecommendations(recommendations);
    }

    /**
     * 메트릭 값 추출 헬퍼
     */
    private String extractMetricValue(Map<String, Object> metrics, String key) {
        if (metrics.containsKey(key)) {
            Map<String, Object> metric = (Map<String, Object>) metrics.get(key);
            if (metric.containsKey("value")) {
                return String.valueOf(metric.get("value"));
            }
        }
        return "N/A";
    }

    private String extractMetricValue(Map<String, Object> metrics, String key, String field) {
        if (metrics.containsKey(key)) {
            Map<String, Object> metric = (Map<String, Object>) metrics.get(key);
            if (metric.containsKey("values")) {
                Map<String, Object> values = (Map<String, Object>) metric.get("values");
                if (values.containsKey(field)) {
                    return String.format("%.2f", values.get(field));
                }
            }
        }
        return "N/A";
    }

    /**
     * 에러 보고서 생성
     */
    private TestReportDTO createErrorReport(String testId, String testName,
                                             LocalDateTime timestamp, long duration, Exception e) {
        TestReportDTO report = new TestReportDTO();
        report.setReportId(UUID.randomUUID().toString());
        report.setTestId(testId);
        report.setTestName(testName);
        report.setStatus("failure");
        report.setTimestamp(timestamp);
        report.setDuration(formatDuration(duration));
        report.setAiSummary("테스트 실행 중 오류가 발생했습니다: " + e.getMessage());
        report.setMetrics(Map.of("Error", e.getClass().getSimpleName()));
        report.setRecommendations(List.of("시스템 로그를 확인하고 서비스 상태를 점검하세요."));
        report.setRawData(Map.of("error", e.getMessage(), "stackTrace", Arrays.toString(e.getStackTrace())));

        saveReport(report);
        return report;
    }

    /**
     * 보고서 저장
     */
    private void saveReport(TestReportDTO report) {
        reportById.put(report.getReportId(), report);

        reportHistory.computeIfAbsent(report.getTestId(), k -> new CopyOnWriteArrayList<>())
                .add(0, report); // 최신 순으로 추가

        // 최대 100개까지만 유지
        List<TestReportDTO> history = reportHistory.get(report.getTestId());
        if (history.size() > 100) {
            history.remove(history.size() - 1);
        }

        logger.info("Report saved: reportId={}, testId={}", report.getReportId(), report.getTestId());
    }

    /**
     * 최근 보고서 조회
     */
    public List<TestReportDTO> getRecentReports(int limit) {
        return reportHistory.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(TestReportDTO::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 특정 테스트 히스토리 조회
     */
    public List<TestReportDTO> getTestHistory(String testId, int limit) {
        return reportHistory.getOrDefault(testId, Collections.emptyList()).stream()
                .limit(limit)
                .toList();
    }

    /**
     * 보고서 상세 조회
     */
    public TestReportDTO getReport(String reportId) {
        return reportById.get(reportId);
    }

    /**
     * Duration 포맷팅
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds);
        } else {
            return String.format("%d초", seconds);
        }
    }
}
