package com.example.payment.service;

import com.example.payment.config.RateLimitProperties;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties rateLimitProperties;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties rateLimitProperties) {
        this.redisTemplate = redisTemplate;
        this.rateLimitProperties = rateLimitProperties;
    }

    public void verifyAuthorizeAllowed(String merchantId) {
        enforceLimit("authorize", merchantId, rateLimitProperties.getAuthorize());
    }

    public void verifyCaptureAllowed(String merchantId) {
        enforceLimit("capture", merchantId, rateLimitProperties.getCapture());
    }

    public void verifyRefundAllowed(String merchantId) {
        enforceLimit("refund", merchantId, rateLimitProperties.getRefund());
    }

    private void enforceLimit(String action, String merchantId, RateLimitProperties.Policy policy) {
        if (policy.getCapacity() <= 0 || policy.getWindowSeconds() <= 0) {
            return; // effectively disabled
        }

        String key = rateLimitKey(action, merchantId);
        try {
            // LUA Script for atomic increment and expire
            // Returns a List: [current_count, ttl_seconds]
            // Usage: EVAL script 1 key capacity window_seconds
            String luaScript =
                "local current = redis.call('INCR', KEYS[1]) " +
                "if current == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
                "end " +
                "local ttl = redis.call('TTL', KEYS[1]) " +
                "return {current, ttl}";

            org.springframework.data.redis.core.script.DefaultRedisScript<java.util.List> redisScript =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, java.util.List.class);

            java.util.List<Long> result = redisTemplate.execute(redisScript, java.util.Collections.singletonList(key),
                    String.valueOf(policy.getCapacity()), String.valueOf(policy.getWindowSeconds()));

            if (result == null || result.isEmpty()) {
                log.warn("Rate limiter script returned null for action={}, merchant={}", action, merchantId);
                return;
            }

            Long count = ((Number) result.get(0)).longValue();
            Long ttl = ((Number) result.get(1)).longValue();

            if (count > policy.getCapacity()) {
                long remaining = ttl != null ? ttl : policy.getWindowSeconds();
                if (remaining < 0) {
                    remaining = policy.getWindowSeconds();
                }
                throw new RateLimitExceededException(
                        String.format(Locale.ROOT,
                                "Rate limit exceeded for %s requests. Try again in %d seconds.",
                                action, Math.max(1, remaining)),
                        "RATE_LIMIT_EXCEEDED",
                        merchantId
                );
            }
        } catch (DataAccessException ex) {
            log.warn("Redis access failed during rate limiting for action={}, merchant={}", action, merchantId, ex);
        }
    }

    private String rateLimitKey(String action, String merchantId) {
        return "rate:" + action + ":" + merchantId;
    }
}
