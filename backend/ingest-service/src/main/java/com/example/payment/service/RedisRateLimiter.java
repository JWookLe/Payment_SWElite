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
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(policy.getWindowSeconds()));
            }

            if (count == null) {
                log.warn("Rate limiter increment returned null for action={}, merchant={}", action, merchantId);
                return;
            }

            if (count > policy.getCapacity()) {
                Long ttlSeconds = redisTemplate.getExpire(key);
                long remaining = ttlSeconds != null ? ttlSeconds : policy.getWindowSeconds();
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
