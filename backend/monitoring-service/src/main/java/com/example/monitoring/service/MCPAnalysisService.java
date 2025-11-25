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
 * Anthropic Claude API를 직접 호출하여 AI 분석 수행
 */
@Service
public class MCPAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(MCPAnalysisService.class);
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

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
            logger.info("Calling Claude API for K6 analysis: testId={}, scenario={}", testId, scenario);

            String prompt = buildK6AnalysisPrompt(scenario, rawData);
            String analysis = callClaudeAPI(prompt);
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
            logger.info("Calling Claude API for Circuit Breaker analysis: testId={}", testId);

            String prompt = buildCircuitBreakerAnalysisPrompt(rawData);
            String analysis = callClaudeAPI(prompt);
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
            logger.info("Calling Claude API for Health Check analysis: testId={}", testId);

            String prompt = buildHealthCheckAnalysisPrompt(rawData);
            String analysis = callClaudeAPI(prompt);
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
            logger.info("Calling Claude API for {} stats analysis: testId={}", testType, testId);

            String prompt = buildMonitoringStatsAnalysisPrompt(testType, rawData);
            String analysis = callClaudeAPI(prompt);
            return parseAnalysisResult(analysis, rawData);

        } catch (Exception e) {
            logger.error("Failed to call Claude API for {} stats analysis", testType, e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * Anthropic Claude API 호출
     */
    private String callClaudeAPI(String prompt) throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("ANTHROPIC_API_KEY environment variable is not set");
        }

        Map<String, Object> requestBody = new HashMap<>();

        // Claude API 요청 형식
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);

        requestBody.put("model", "claude-3-5-haiku-20241022");
        requestBody.put("max_tokens", 2048);
        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            var response = restTemplate.postForObject(CLAUDE_API_URL, request, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = (Map<String, Object>) response;

            if (response != null && responseMap.containsKey("content")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap.get("content");
                if (content != null && !content.isEmpty()) {
                    Map<String, Object> firstContent = content.get(0);
                    if (firstContent.containsKey("text")) {
                        return (String) firstContent.get("text");
                    }
                }
            }
            throw new RuntimeException("Invalid Claude API response");
        } catch (Exception e) {
            logger.error("Claude API call failed", e);
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
        return "당신은 성능 테스트 전문가입니다. 다음 K6 부하 테스트 결과를 분석하고 한글로 제공하세요:\n\n" +
               "1. **요약** (2-3문장)\n" +
               "2. **성능 메트릭 분석**\n" +
               "3. **병목 지점 및 문제점**\n" +
               "4. **개선 권장사항** (3-5개)\n\n" +
               "테스트 시나리오: " + scenario + "\n\n" +
               "원본 데이터:\n" + rawDataJson;
    }

    /**
     * Circuit Breaker 분석 프롬프트 생성
     */
    private String buildCircuitBreakerAnalysisPrompt(Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "당신은 회복탄력성 엔지니어입니다. 다음 Circuit Breaker 테스트 결과를 분석하고 한글로 제공하세요:\n\n" +
               "다음을 포함하세요:\n" +
               "1. **요약**: Circuit Breaker가 올바르게 작동했는가?\n" +
               "2. **상태 전환**: CLOSED → OPEN → HALF_OPEN → CLOSED 전환 분석\n" +
               "3. **복구 동작**: 시스템 복구 평가\n" +
               "4. **개선 권장사항**: 설정 조정 제안\n\n" +
               "테스트 데이터:\n" + rawDataJson;
    }

    /**
     * Health Check 분석 프롬프트 생성
     */
    private String buildHealthCheckAnalysisPrompt(Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "당신은 DevOps 엔지니어입니다. 다음 시스템 Health Check 결과를 분석하고 한글로 제공하세요:\n\n" +
               "다음을 포함하세요:\n" +
               "1. **요약**: 전체 시스템 상태\n" +
               "2. **서비스 상태**: 각 서비스별 상태 (UP/DOWN)\n" +
               "3. **의존성**: 연결성 평가\n" +
               "4. **조치 권장사항**: 필요한 행동\n\n" +
               "Health Check 데이터:\n" + rawDataJson;
    }

    /**
     * 모니터링 통계 분석 프롬프트 생성
     */
    private String buildMonitoringStatsAnalysisPrompt(String testType, Map<String, Object> rawData) throws Exception {
        String rawDataJson = objectMapper.writeValueAsString(rawData);
        return "당신은 모니터링 및 관찰성 전문가입니다. 다음 " + testType + " 통계를 분석하고 한글로 제공하세요:\n\n" +
               "다음을 포함하세요:\n" +
               "1. **요약**: 데이터에서 얻은 핵심 인사이트\n" +
               "2. **메트릭 분석**: 성능 및 상태 메트릭 평가\n" +
               "3. **트렌드**: 우려스러운 추세 또는 이상 현상 식별\n" +
               "4. **개선 권장사항**: 최적화 제안\n\n" +
               "모니터링 데이터:\n" + rawDataJson;
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

        summary.append("상세 분석을 위해서는 Claude API 연동이 필요합니다.\n");
        summary.append("ANTHROPIC_API_KEY 환경 변수를 설정하세요.");

        result.put("aiSummary", summary.toString());
        result.put("metrics", Map.of("Status", "Data Collected"));
        result.put("recommendations", List.of("Claude API를 활성화하여 AI 분석을 받으세요."));

        return result;
    }
}
