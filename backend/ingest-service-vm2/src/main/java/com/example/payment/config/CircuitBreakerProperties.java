package com.example.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit Breaker configuration properties.
 * Allows fine-tuning of circuit breaker behavior via environment variables or config files.
 */
@ConfigurationProperties(prefix = "app.circuit-breaker")
public class CircuitBreakerProperties {

    private KafkaPublisherPolicy kafkaPublisher = new KafkaPublisherPolicy();

    public KafkaPublisherPolicy getKafkaPublisher() {
        return kafkaPublisher;
    }

    public void setKafkaPublisher(KafkaPublisherPolicy kafkaPublisher) {
        this.kafkaPublisher = kafkaPublisher;
    }

    public static class KafkaPublisherPolicy {
        private int failureThresholdPercentage = 50;
        private int waitDurationInOpenState = 30000; // milliseconds
        private int permittedCallsInHalfOpenState = 3;
        private int minimumNumberOfCalls = 5;
        private int slowCallDurationThreshold = 5000; // milliseconds
        private int slowCallRateThreshold = 50; // percentage

        public int getFailureThresholdPercentage() {
            return failureThresholdPercentage;
        }

        public void setFailureThresholdPercentage(int failureThresholdPercentage) {
            this.failureThresholdPercentage = failureThresholdPercentage;
        }

        public int getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(int waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public int getSlowCallDurationThreshold() {
            return slowCallDurationThreshold;
        }

        public void setSlowCallDurationThreshold(int slowCallDurationThreshold) {
            this.slowCallDurationThreshold = slowCallDurationThreshold;
        }

        public int getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(int slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }
    }
}
