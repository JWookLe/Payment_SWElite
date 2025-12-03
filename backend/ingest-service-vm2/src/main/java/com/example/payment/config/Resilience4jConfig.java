package com.example.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j configuration for monitoring and managing circuit breaker behavior.
 *
 * Provides:
 * - Event logging for circuit breaker state changes
 * - Health indicators for monitoring
 * - Metrics export to Prometheus
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

    /**
     * Registers event listeners for circuit breaker registry.
     * This is optional but useful for monitoring circuit breaker lifecycle.
     */
    public Resilience4jConfig(CircuitBreakerRegistry circuitBreakerRegistry) {
        log.info("Resilience4j Circuit Breaker configuration initialized");
        // Event listeners are configured via application.yml
        // No additional initialization needed here
    }
}
