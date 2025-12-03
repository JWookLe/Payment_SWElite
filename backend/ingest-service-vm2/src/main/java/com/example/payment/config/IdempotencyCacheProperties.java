package com.example.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.idempotency-cache")
public class IdempotencyCacheProperties {

    /**
     * TTL in seconds for Redis cached responses.
     */
    private long ttlSeconds = 600;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
