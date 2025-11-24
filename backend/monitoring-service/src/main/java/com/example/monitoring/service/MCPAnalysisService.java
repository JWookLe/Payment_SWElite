package com.example.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Google Gemini API를 직접 호출하여 AI 분석 수행
 */
@Service
public class MCPAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(MCPAnalysisService.class);
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent";

    @Value("${mcp.ai-analyzer.enabled:true}")
    private boolean mcpEnabled;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public MCPAnalysisService(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * K6 테스트 결과 AI 분석
     */
    public Map<String, Object> analyzeK6Test(String testId, String scenario, Map<String, Object> rawData) {
        if (!mcpEnabled) {
            logger.info("AI analysis is disabled, using fallback");
            return generateFallbackAnalysis(rawData);
        }

        try {
            logger.info("Calling Gemini API for K6 analysis: testId={}, scenario={}", testId, scenario);

            String prompt = buildK6AnalysisPrompt(scenario, rawData);
            String analysis = callGeminiAPI(prompt);
            return parseAnalysisResult(analysis, rawData);

        } catch (Exception e) {
            logger.error("Failed to call Claude API for K6 analysis", e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * Circuit Breaker 테스트 분석
     */
    public Map<String, Object> analyzeCircuitBreakerTest(String testId, Map<String, Object> rawData) {
        if (!mcpEnabled) {
            return generateFallbackAnalysis(rawData);
        }

        try {
            logger.info("Calling Gemini API for Circuit Breaker analysis: testId={}", testId);

            String prompt = buildCircuitBreakerAnalysisPrompt(rawData);
            String analysis = callGeminiAPI(prompt);
            return parseAnalysisResult(analysis, rawData);

        } catch (Exception e) {
            logger.error("Failed to call Claude API for Circuit Breaker analysis", e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * Health Check 분석
     */
    public Map<String, Object> analyzeHealthCheck(String testId, Map<String, Object> rawData) {
        if (!mcpEnabled) {
            return generateFallbackAnalysis(rawData);
        }

        try {
            logger.info("Calling Gemini API for Health Check analysis: testId={}", testId);

            String prompt = buildHealthCheckAnalysisPrompt(rawData);
            String analysis = callGeminiAPI(prompt);
            return parseAnalysisResult(analysis, rawData);

        } catch (Exception e) {
            logger.error("Failed to call Claude API for Health Check analysis", e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * 모니터링 통계 분석
     */
    public Map<String, Object> analyzeMonitoringStats(String testId, String testType, Map<String, Object> rawData) {
        if (!mcpEnabled) {
            return generateFallbackAnalysis(rawData);
        }

        try {
            logger.info("Calling Gemini API for {} stats analysis: testId={}", testType, testId);

            String prompt = buildMonitoringStatsAnalysisPrompt(testType, rawData);
            String analysis = callGeminiAPI(prompt);
            return parseAnalysisResult(analysis, rawData);

        } catch (Exception e) {
            logger.error("Failed to call Claude API for {} stats analysis", testType, e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * Google Gemini API 호출
     */
    private String callGeminiAPI(String prompt) throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("GEMINI_API_KEY environment variable is not set");
        }

        Map<String, Object> requestBody = new HashMap<>();

        // Gemini API 요청 형식
        Map<String, Object> content = new HashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        content.put("parts", parts);

        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", Map.of(
            "maxOutputTokens", 2048,
            "temperature", 0.7
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            String urlWithKey = GEMINI_API_URL + "?key=" + apiKey;
            var response = restTemplate.postForObject(urlWithKey, request, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = (Map<String, Object>) response;

            if (response != null && responseMap.containsKey("candidates")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> candidate = candidates.get(0);
                    if (candidate.containsKey("content")) {
                        Map<String, Object> contentResp = (Map<String, Object>) candidate.get("content");
                        if (contentResp.containsKey("parts")) {
                            List<Map<String, Object>> partsResp = (List<Map<String, Object>>) contentResp.get("parts");
                            if (partsResp != null && !partsResp.isEmpty()) {
                                return (String) partsResp.get(0).get("text");
                            }
                        }
                    }
                }
            }
            throw new RuntimeException("Invalid Gemini API response");
        } catch (Exception e) {
            logger.error("Gemini API call failed", e);
            throw e;
        }
    }

    /**
     * 분석 결과 파싱
     */
    private Map<String, Object> parseAnalysisResult(String analysisText, Map<String, Object> rawData) {
        Map<String, Object> result = new HashMap<>();
        result.put("aiSummary", analysisText);
        result.put("metrics", extractMetrics(rawData));
        result.put("recommendations", extractRecommendations(analysisText));
        return result;
    }

    /**
     * 메트릭 추출
     */
    private Map<String, String> extractMetrics(Map<String, Object> rawData) {
        Map<String, String> metrics = new HashMap<>();
        if (rawData.containsKey("k6Summary")) {
            Map<String, Object> k6Summary = (Map<String, Object>) rawData.get("k6Summary");
            if (k6Summary.containsKey("metrics")) {
                Map<String, Object> k6Metrics = (Map<String, Object>) k6Summary.get("metrics");
                if (k6Metrics.containsKey("http_reqs")) {
                    Map<String, Object> httpReqs = (Map<String, Object>) k6Metrics.get("http_reqs");
                    if (httpReqs.containsKey("values")) {
                        Map<String, Object> values = (Map<String, Object>) httpReqs.get("values");
                        metrics.put("Total Requests", String.valueOf(values.getOrDefault("count", "N/A")));
                    }
                }
            }
        }
        if (metrics.isEmpty()) {
            metrics.put("Status", "Data Collected");
        }
        return metrics;
    }

    /**
     * 권장사항 추출
     */
    private List<String> extractRecommendations(String analysisText) {
        List<String> recommendations = new ArrayList<>();
        String[] lines = analysisText.split("\n");
        boolean inRecommendations = false;

        for (String line : lines) {
            if (line.toLowerCase().contains("recommendation")) {
                inRecommendations = true;
                continue;
            }
            if (inRecommendations && (line.trim().startsWith("-") || line.trim().startsWith("•") || line.trim().matches("^\\d+\\..*"))) {
                String rec = line.trim().replaceAll("^[-•]\\s*", "").replaceAll("^\\d+\\.\\s*", "");
                if (!rec.isEmpty()) {
                    recommendations.add(rec);
                }
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("AI 분석을 참고하세요.");
        }
        return recommendations;
    }

    /**
     * K6 분석 프롬프트 생성
     */
    private String buildK6AnalysisPrompt(String scenario, Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "You are an expert performance testing analyst. Analyze the following K6 load test results and provide:\n\n" +
               "1. **Executive Summary** (2-3 sentences)\n" +
               "2. **Performance Metrics Analysis**\n" +
               "3. **Bottlenecks & Issues**\n" +
               "4. **Recommendations** (3-5 bullet points)\n\n" +
               "Test Scenario: " + scenario + "\n\n" +
               "Raw Data:\n" + rawDataJson;
    }

    /**
     * Circuit Breaker 분석 프롬프트 생성
     */
    private String buildCircuitBreakerAnalysisPrompt(Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "You are a resilience engineering expert. Analyze the following Circuit Breaker test results:\n\n" +
               "Provide:\n" +
               "1. **Summary**: Did the circuit breaker function correctly?\n" +
               "2. **State Transitions**: Analyze CLOSED → OPEN → HALF_OPEN → CLOSED transitions\n" +
               "3. **Recovery Behavior**: Evaluate system recovery\n" +
               "4. **Recommendations**: Tuning suggestions\n\n" +
               "Test Data:\n" + rawDataJson;
    }

    /**
     * Health Check 분석 프롬프트 생성
     */
    private String buildHealthCheckAnalysisPrompt(Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "You are a DevOps engineer analyzing system health checks. Review the following health check results:\n\n" +
               "Provide:\n" +
               "1. **Summary**: Overall system health status\n" +
               "2. **Service Status**: Breakdown of each service (UP/DOWN)\n" +
               "3. **Dependencies**: Evaluate connectivity\n" +
               "4. **Recommendations**: Actions needed\n\n" +
               "Health Check Data:\n" + rawDataJson;
    }

    /**
     * 모니터링 통계 분석 프롬프트 생성
     */
    private String buildMonitoringStatsAnalysisPrompt(String testType, Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "You are a monitoring and observability expert. Analyze the following " + testType + " statistics:\n\n" +
               "Provide:\n" +
               "1. **Summary**: Key insights from the data\n" +
               "2. **Metrics Analysis**: Evaluate performance and health metrics\n" +
               "3. **Trends**: Identify concerning trends or anomalies\n" +
               "4. **Recommendations**: Optimization suggestions\n\n" +
               "Monitoring Data:\n" + rawDataJson;
    }

    /**
     * Fallback 분석 (MCP 실패 시)
     */
    private Map<String, Object> generateFallbackAnalysis(Map<String, Object> rawData) {
        Map<String, Object> result = new HashMap<>();

        StringBuilder summary = new StringBuilder();
        summary.append("=== 기본 분석 보고서 ===\n\n");

        if (rawData.containsKey("exitCode")) {
            int exitCode = (int) rawData.get("exitCode");
            if (exitCode == 0) {
                summary.append("✓ 테스트가 성공적으로 완료되었습니다.\n\n");
            } else {
                summary.append("✗ 테스트가 실패했습니다 (Exit Code: ").append(exitCode).append(")\n\n");
            }
        }

        summary.append("상세 분석을 위해서는 Google Gemini API 연동이 필요합니다.\n");
        summary.append("GEMINI_API_KEY 환경 변수를 설정하세요.");

        result.put("aiSummary", summary.toString());
        result.put("metrics", Map.of("Status", "Data Collected"));
        result.put("recommendations", List.of("Google Gemini API를 활성화하여 AI 분석을 받으세요."));

        return result;
    }
}
