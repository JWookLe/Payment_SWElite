package com.example.payment.web;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for monitoring circuit breaker status.
 * Provides real-time visibility into circuit breaker states.
 *
 * Endpoints:
 * - GET /circuit-breaker/status - Get all circuit breakers status
 * - GET /circuit-breaker/kafka-publisher - Get Kafka publisher circuit breaker details
 */
@RestController
@RequestMapping("/circuit-breaker")
public class CircuitBreakerStatusController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerStatusController(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerStatus() {
        Map<String, Object> response = new HashMap<>();
        CircuitBreaker kafkaPublisherCB = circuitBreakerRegistry.find("kafka-publisher").orElse(null);

        if (kafkaPublisherCB != null) {
            response.put("kafka-publisher", buildCircuitBreakerInfo(kafkaPublisherCB));
        } else {
            response.put("kafka-publisher", "Not Found");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/kafka-publisher")
    public ResponseEntity<Map<String, Object>> getKafkaPublisherStatus() {
        CircuitBreaker kafkaPublisherCB = circuitBreakerRegistry.find("kafka-publisher")
                .orElseThrow(() -> new IllegalArgumentException("Circuit breaker not found"));

        return ResponseEntity.ok(buildCircuitBreakerInfo(kafkaPublisherCB));
    }

    private Map<String, Object> buildCircuitBreakerInfo(CircuitBreaker circuitBreaker) {
        var metrics = circuitBreaker.getMetrics();
        var state = circuitBreaker.getState();

        Map<String, Object> info = new HashMap<>();
        info.put("state", state.toString());
        info.put("numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls());
        info.put("numberOfFailedCalls", metrics.getNumberOfFailedCalls());
        info.put("numberOfSlowCalls", metrics.getNumberOfSlowCalls());
        info.put("numberOfSlowSuccessfulCalls", metrics.getNumberOfSlowSuccessfulCalls());
        info.put("numberOfSlowFailedCalls", metrics.getNumberOfSlowFailedCalls());
        info.put("numberOfBufferedCalls", metrics.getNumberOfBufferedCalls());
        info.put("numberOfNotPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        info.put("failureRate", String.format("%.2f%%", metrics.getFailureRate()));
        info.put("slowCallRate", String.format("%.2f%%", metrics.getSlowCallRate()));

        return info;
    }
}
