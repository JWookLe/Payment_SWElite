package com.example.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

/**
 * MCP 서버와 통신하여 AI 분석 수행
 */
@Service
public class MCPAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(MCPAnalysisService.class);

    @Value("${mcp.ai-analyzer.enabled:true}")
    private boolean mcpEnabled;

    @Value("${mcp.ai-analyzer.path:mcp-servers/ai-test-analyzer}")
    private String mcpServerPath;

    private final ObjectMapper objectMapper;

    public MCPAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * K6 테스트 결과 AI 분석
     */
    public Map<String, Object> analyzeK6Test(String testId, String scenario, Map<String, Object> rawData) {
        if (!mcpEnabled) {
            logger.info("MCP analysis is disabled, using fallback");
            return generateFallbackAnalysis(rawData);
        }

        try {
            logger.info("Calling MCP server for K6 analysis: testId={}, scenario={}", testId, scenario);

            Map<String, Object> request = new HashMap<>();
            request.put("method", "tools/call");
            request.put("params", Map.of(
                "name", "analyze_k6_test",
                "arguments", Map.of(
                    "testId", testId,
                    "scenario", scenario,
                    "rawData", rawData
                )
            ));

            String response = callMCPServer(request);
            return parseResponse(response);

        } catch (Exception e) {
            logger.error("Failed to call MCP server for K6 analysis", e);
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
            logger.info("Calling MCP server for Circuit Breaker analysis: testId={}", testId);

            Map<String, Object> request = new HashMap<>();
            request.put("method", "tools/call");
            request.put("params", Map.of(
                "name", "analyze_circuit_breaker_test",
                "arguments", Map.of(
                    "testId", testId,
                    "rawData", rawData
                )
            ));

            String response = callMCPServer(request);
            return parseResponse(response);

        } catch (Exception e) {
            logger.error("Failed to call MCP server for Circuit Breaker analysis", e);
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
            logger.info("Calling MCP server for Health Check analysis: testId={}", testId);

            Map<String, Object> request = new HashMap<>();
            request.put("method", "tools/call");
            request.put("params", Map.of(
                "name", "analyze_health_check",
                "arguments", Map.of(
                    "testId", testId,
                    "rawData", rawData
                )
            ));

            String response = callMCPServer(request);
            return parseResponse(response);

        } catch (Exception e) {
            logger.error("Failed to call MCP server for Health Check analysis", e);
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
            logger.info("Calling MCP server for {} stats analysis: testId={}", testType, testId);

            Map<String, Object> request = new HashMap<>();
            request.put("method", "tools/call");
            request.put("params", Map.of(
                "name", "analyze_monitoring_stats",
                "arguments", Map.of(
                    "testId", testId,
                    "testType", testType,
                    "rawData", rawData
                )
            ));

            String response = callMCPServer(request);
            return parseResponse(response);

        } catch (Exception e) {
            logger.error("Failed to call MCP server for {} stats analysis", testType, e);
            return generateFallbackAnalysis(rawData);
        }
    }

    /**
     * MCP 서버 호출 (stdio 기반)
     */
    private String callMCPServer(Map<String, Object> request) throws Exception {
        File serverDir = new File(mcpServerPath);
        if (!serverDir.exists()) {
            throw new RuntimeException("MCP server directory not found: " + mcpServerPath);
        }

        // Node.js로 MCP 서버 실행
        ProcessBuilder pb = new ProcessBuilder(
            "node",
            "dist/index.js"
        );
        pb.directory(serverDir);

        // 환경 변수 설정
        Map<String, String> env = pb.environment();
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null) {
            env.put("ANTHROPIC_API_KEY", apiKey);
        }

        pb.redirectErrorStream(false);

        Process process = pb.start();

        // 요청 전송
        try (OutputStream stdin = process.getOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(stdin)) {

            String requestJson = objectMapper.writeValueAsString(request);
            logger.debug("Sending to MCP: {}", requestJson);

            writer.write(requestJson);
            writer.write("\n");
            writer.flush();
        }

        // 응답 수신
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
                break; // 첫 번째 줄만 읽기 (JSON 응답)
            }
        }

        // 에러 로그 읽기
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
                logger.debug("MCP stderr: {}", errorLine);
            }
        }

        process.destroy();

        String responseStr = response.toString();
        logger.debug("Received from MCP: {}", responseStr);

        return responseStr;
    }

    /**
     * MCP 응답 파싱
     */
    private Map<String, Object> parseResponse(String response) throws Exception {
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);

        // MCP 응답 구조: { content: [{ type: "text", text: "..." }] }
        List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap.get("content");
        if (content != null && !content.isEmpty()) {
            String textContent = (String) content.get(0).get("text");
            // JSON 형태로 파싱
            return objectMapper.readValue(textContent, Map.class);
        }

        throw new RuntimeException("Invalid MCP response format");
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

        summary.append("상세 분석을 위해서는 MCP 서버와 Claude API 연동이 필요합니다.\n");
        summary.append("ANTHROPIC_API_KEY 환경 변수를 설정하고 MCP 서버를 활성화하세요.");

        result.put("aiSummary", summary.toString());
        result.put("metrics", Map.of("Status", "Data Collected"));
        result.put("recommendations", List.of("MCP 서버를 활성화하여 AI 분석을 받으세요."));

        return result;
    }
}
