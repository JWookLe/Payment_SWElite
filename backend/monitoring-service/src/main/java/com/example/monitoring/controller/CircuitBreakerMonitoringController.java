package com.example.monitoring.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Circuit Breaker monitoring
 * Provides same functionality as circuit-breaker-mcp but via HTTP
 */
@RestController
@RequestMapping("/monitoring/circuit-breaker")
public class CircuitBreakerMonitoringController {

    private final WebClient webClient;
    private final String ingestServiceUrl;

    public CircuitBreakerMonitoringController(
            WebClient.Builder webClientBuilder,
            @Value("${services.ingest-service-vm1.url}") String ingestServiceUrl) {
        this.webClient = webClientBuilder.build();
        this.ingestServiceUrl = ingestServiceUrl;
    }

    /**
     * GET /monitoring/circuit-breaker/status
     * Returns Circuit Breaker status with analysis
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        try {
            // Call ingest-service circuit breaker endpoint
            Map<String, Object> cbStatus = webClient
                    .get()
                    .uri(ingestServiceUrl + "/circuit-breaker/kafka-publisher")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (cbStatus == null) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Unable to fetch circuit breaker status"
                ));
            }

            // Add analysis
            Map<String, Object> response = new HashMap<>(cbStatus);
            response.put("analysis", analyzeCircuitBreaker(cbStatus));
            response.put("healthStatus", getHealthStatus(cbStatus));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Circuit breaker service unavailable",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/circuit-breaker/health
     * Simple health check - is Kafka working?
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        try {
            Map<String, Object> cbStatus = webClient
                    .get()
                    .uri(ingestServiceUrl + "/circuit-breaker/kafka-publisher")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (cbStatus == null) {
                return ResponseEntity.ok(Map.of(
                        "healthy", false,
                        "message", "Cannot reach circuit breaker"
                ));
            }

            String state = (String) cbStatus.get("state");
            String failureRate = (String) cbStatus.getOrDefault("failureRate", "0.00%");
            double failureRateValue = parsePercentage(failureRate);

            boolean isHealthy = "CLOSED".equals(state) && failureRateValue < 10.0;

            return ResponseEntity.ok(Map.of(
                    "healthy", isHealthy,
                    "state", state,
                    "failureRate", failureRate,
                    "message", isHealthy ? "Kafka is healthy" : "Kafka issues detected"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "healthy", false,
                    "message", "Error checking health: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/circuit-breaker/diagnose
     * Detailed diagnosis with recommendations
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose() {
        try {
            Map<String, Object> cbStatus = webClient
                    .get()
                    .uri(ingestServiceUrl + "/circuit-breaker/kafka-publisher")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (cbStatus == null) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Unable to fetch circuit breaker status"
                ));
            }

            String state = (String) cbStatus.get("state");
            double failureRate = parsePercentage((String) cbStatus.getOrDefault("failureRate", "0.00%"));
            double slowCallRate = parsePercentage((String) cbStatus.getOrDefault("slowCallRate", "0.00%"));
            int notPermittedCalls = ((Number) cbStatus.getOrDefault("numberOfNotPermittedCalls", 0)).intValue();

            Map<String, Object> diagnosis = new HashMap<>();
            diagnosis.put("state", state);
            diagnosis.put("failureRate", cbStatus.get("failureRate"));
            diagnosis.put("slowCallRate", cbStatus.get("slowCallRate"));
            diagnosis.put("issues", identifyIssues(state, failureRate, slowCallRate, notPermittedCalls));
            diagnosis.put("recommendations", getRecommendations(state, failureRate, slowCallRate, notPermittedCalls));
            diagnosis.put("metrics", cbStatus);

            return ResponseEntity.ok(diagnosis);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Diagnosis failed",
                    "message", e.getMessage()
            ));
        }
    }

    private Map<String, String> analyzeCircuitBreaker(Map<String, Object> status) {
        String state = (String) status.get("state");
        Map<String, String> analysis = new HashMap<>();

        switch (state) {
            case "CLOSED":
                analysis.put("status", "Normal");
                analysis.put("description", "Circuit breaker is closed. Kafka is operational.");
                break;
            case "OPEN":
                analysis.put("status", "Critical");
                analysis.put("description", "Circuit breaker is open. Kafka publishing is blocked.");
                break;
            case "HALF_OPEN":
                analysis.put("status", "Warning");
                analysis.put("description", "Circuit breaker is testing Kafka recovery.");
                break;
            default:
                analysis.put("status", "Unknown");
                analysis.put("description", "Unknown circuit breaker state: " + state);
        }

        return analysis;
    }

    private String getHealthStatus(Map<String, Object> status) {
        String state = (String) status.get("state");
        double failureRate = parsePercentage((String) status.getOrDefault("failureRate", "0.00%"));

        if ("OPEN".equals(state)) {
            return "UNHEALTHY";
        } else if ("HALF_OPEN".equals(state)) {
            return "RECOVERING";
        } else if (failureRate > 20) {
            return "DEGRADED";
        } else {
            return "HEALTHY";
        }
    }

    private java.util.List<String> identifyIssues(String state, double failureRate, double slowCallRate, int notPermittedCalls) {
        java.util.List<String> issues = new java.util.ArrayList<>();

        if ("OPEN".equals(state)) {
            issues.add("Circuit Breaker is OPEN - Kafka publishing is blocked");
        } else if ("HALF_OPEN".equals(state)) {
            issues.add("Circuit Breaker is HALF_OPEN - Testing Kafka recovery");
        }

        if (failureRate >= 50) {
            issues.add("High failure rate: " + String.format("%.2f%%", failureRate));
        }

        if (slowCallRate >= 50) {
            issues.add("High slow call rate: " + String.format("%.2f%%", slowCallRate));
        }

        if (notPermittedCalls > 0) {
            issues.add(notPermittedCalls + " calls were rejected by Circuit Breaker");
        }

        if (issues.isEmpty()) {
            issues.add("No issues detected - Circuit Breaker is healthy");
        }

        return issues;
    }

    private java.util.List<String> getRecommendations(String state, double failureRate, double slowCallRate, int notPermittedCalls) {
        java.util.List<String> recommendations = new java.util.ArrayList<>();

        if ("OPEN".equals(state)) {
            recommendations.add("Check if Kafka broker is running: docker compose ps kafka");
            recommendations.add("Check Kafka logs: docker compose logs kafka --tail=50");
            recommendations.add("Wait for automatic transition to HALF_OPEN (30 seconds)");
        } else if ("HALF_OPEN".equals(state)) {
            recommendations.add("Monitor next 3 test calls carefully");
            recommendations.add("If calls succeed, will transition to CLOSED automatically");
        }

        if (failureRate >= 50) {
            recommendations.add("Check Kafka connectivity and broker health");
            recommendations.add("Review application logs for KafkaException");
        }

        if (slowCallRate >= 50) {
            recommendations.add("Kafka may be overloaded or network latency is high");
            recommendations.add("Check Prometheus metrics for kafka producer latency");
        }

        if (notPermittedCalls > 0) {
            recommendations.add("These payments were saved to outbox_event table but NOT published");
            recommendations.add("After Kafka recovery, manually republish using OutboxEventPublisher");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("No action required - system is operating normally");
        }

        return recommendations;
    }

    private double parsePercentage(String percentage) {
        try {
            return Double.parseDouble(percentage.replace("%", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
