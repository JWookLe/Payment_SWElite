package com.example.monitoring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/monitoring/loadtest")
public class LoadTestMonitoringController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String K6_RESULTS_DIR = System.getenv().getOrDefault("K6_RESULTS_DIR", "./loadtest/k6");
    private static final String K6_SCRIPT_PATH = System.getenv().getOrDefault("K6_SCRIPT_PATH", "./loadtest/k6/payment-scenario.js");

    /**
     * 최근 k6 테스트 결과 조회
     */
    @GetMapping("/latest-result")
    public Map<String, Object> getLatestResult() {
        try {
            Path summaryPath = Paths.get(K6_RESULTS_DIR, "summary.json");

            if (!Files.exists(summaryPath)) {
                return Map.of(
                        "found", false,
                        "message", "No test results found. Run a k6 test first."
                );
            }

            String content = Files.readString(summaryPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = objectMapper.readValue(content, Map.class);

            return Map.of(
                    "found", true,
                    "summary", summary,
                    "filePath", summaryPath.toAbsolutePath().toString(),
                    "message", "Latest k6 test result retrieved successfully"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to read test results: " + e.getMessage()
            );
        }
    }

    /**
     * k6 테스트 결과 분석 (성능 지표 요약)
     */
    @GetMapping("/analyze")
    public Map<String, Object> analyzeResults() {
        try {
            Path summaryPath = Paths.get(K6_RESULTS_DIR, "summary.json");

            if (!Files.exists(summaryPath)) {
                return Map.of(
                        "found", false,
                        "message", "No test results to analyze"
                );
            }

            String content = Files.readString(summaryPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = objectMapper.readValue(content, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");

            // HTTP 요청 성공률
            @SuppressWarnings("unchecked")
            Map<String, Object> httpReqDuration = (Map<String, Object>) metrics.get("http_req_duration");
            @SuppressWarnings("unchecked")
            Map<String, Object> httpReqFailed = (Map<String, Object>) metrics.get("http_req_failed");

            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) httpReqDuration.get("values");
            Double p95 = (Double) values.get("p(95)");
            Double p99 = (Double) values.get("p(99)");
            Double avg = (Double) values.get("avg");

            @SuppressWarnings("unchecked")
            Map<String, Object> failedValues = (Map<String, Object>) httpReqFailed.get("values");
            Double failRate = (Double) failedValues.get("rate");

            // 처리량 (RPS)
            @SuppressWarnings("unchecked")
            Map<String, Object> iterations = (Map<String, Object>) metrics.get("iterations");
            @SuppressWarnings("unchecked")
            Map<String, Object> iterValues = (Map<String, Object>) iterations.get("values");
            Double rps = (Double) iterValues.get("rate");

            // 성능 평가
            String performanceGrade = evaluatePerformance(p95, failRate);

            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("responseTime", Map.of(
                    "avg", String.format("%.2f ms", avg),
                    "p95", String.format("%.2f ms", p95),
                    "p99", String.format("%.2f ms", p99)
            ));
            analysis.put("successRate", String.format("%.2f%%", (1 - failRate) * 100));
            analysis.put("failureRate", String.format("%.2f%%", failRate * 100));
            analysis.put("throughput", String.format("%.2f req/s", rps));
            analysis.put("performanceGrade", performanceGrade);
            analysis.put("recommendations", generateRecommendations(p95, failRate, rps));

            return Map.of(
                    "found", true,
                    "analysis", analysis,
                    "message", "Performance analysis completed"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to analyze results: " + e.getMessage()
            );
        }
    }

    /**
     * k6 테스트 실행 엔드포인트 (읽기 전용 - 보안상 제거됨)
     *
     * 현업 패턴: 테스트 실행은 CI/CD 파이프라인이나 별도 스크립트로 수행
     * 이 API는 결과 조회만 제공합니다.
     *
     * 테스트 실행 방법:
     * - 호스트에서 scripts/run-k6-test.sh 스크립트 사용
     * - Jenkins/GitLab CI 등 파이프라인에서 실행
     */
    @PostMapping("/run")
    public Map<String, Object> runLoadTest(
            @RequestParam(defaultValue = "false") boolean enableCapture,
            @RequestParam(defaultValue = "false") boolean enableRefund) {
        return Map.of(
                "error", true,
                "message", "Direct test execution is disabled for security reasons. Please use scripts/run-k6-test.sh to run tests from the host machine.",
                "documentation", "See loadtest/k6/README.md for instructions"
        );
    }

    /**
     * k6 테스트 히스토리 (최근 N개)
     */
    @GetMapping("/history")
    public Map<String, Object> getTestHistory(@RequestParam(defaultValue = "5") int limit) {
        try {
            File resultsDir = new File(K6_RESULTS_DIR);
            if (!resultsDir.exists() || !resultsDir.isDirectory()) {
                return Map.of(
                        "count", 0,
                        "history", List.of(),
                        "message", "No test history found"
                );
            }

            File[] files = resultsDir.listFiles((dir, name) -> name.startsWith("summary-") && name.endsWith(".json"));
            if (files == null || files.length == 0) {
                return Map.of(
                        "count", 0,
                        "history", List.of(),
                        "message", "No test history found"
                );
            }

            List<Map<String, Object>> history = Arrays.stream(files)
                    .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                    .limit(limit)
                    .map(this::parseTestSummary)
                    .collect(Collectors.toList());

            return Map.of(
                    "count", history.size(),
                    "history", history,
                    "message", "Test history retrieved successfully"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve test history: " + e.getMessage()
            );
        }
    }

    private Map<String, Object> parseTestSummary(File file) {
        try {
            String content = Files.readString(file.toPath());
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = objectMapper.readValue(content, Map.class);

            return Map.of(
                    "file", file.getName(),
                    "timestamp", file.lastModified(),
                    "summary", summary
            );
        } catch (Exception e) {
            return Map.of(
                    "file", file.getName(),
                    "error", "Failed to parse: " + e.getMessage()
            );
        }
    }

    private String evaluatePerformance(Double p95, Double failRate) {
        if (failRate > 0.01) { // 1% 이상 실패
            return "POOR - High failure rate";
        } else if (p95 > 200) { // p95 > 200ms
            return "FAIR - High latency";
        } else if (p95 > 100) {
            return "GOOD - Acceptable performance";
        } else {
            return "EXCELLENT - Great performance";
        }
    }

    private List<String> generateRecommendations(Double p95, Double failRate, Double rps) {
        List<String> recommendations = new ArrayList<>();

        if (failRate > 0.01) {
            recommendations.add("실패율이 높습니다. 에러 로그를 확인하세요.");
        }

        if (p95 > 200) {
            recommendations.add("응답 시간이 느립니다. DB 쿼리나 외부 API 호출을 최적화하세요.");
        }

        if (rps < 50) {
            recommendations.add("처리량이 낮습니다. 스레드 풀이나 커넥션 풀 크기를 늘려보세요.");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("성능이 양호합니다. 현재 설정을 유지하세요.");
        }

        return recommendations;
    }

    /**
     * k6 시나리오 목록
     */
    @GetMapping("/scenarios")
    public Map<String, Object> listScenarios() {
        return Map.of(
                "scenarios", List.of(
                        Map.of(
                                "name", "authorize-only",
                                "description", "승인만 테스트 (ENABLE_CAPTURE=false, ENABLE_REFUND=false)"
                        ),
                        Map.of(
                                "name", "authorize-capture",
                                "description", "승인 + 정산 테스트 (ENABLE_CAPTURE=true, ENABLE_REFUND=false)"
                        ),
                        Map.of(
                                "name", "full-flow",
                                "description", "전체 플로우 테스트 (ENABLE_CAPTURE=true, ENABLE_REFUND=true)"
                        )
                ),
                "message", "Available k6 test scenarios"
        );
    }
}
