package com.example.payment.service;

import com.example.payment.domain.Payment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Payment Redis Cache Service
 *
 * Caches Payment entities by paymentId for capture and refund operations
 * TTL: 5 minutes (refresh on each read)
 *
 * Benefits:
 * - Reduces DB load for frequently accessed payment records
 * - Improves response time for capture/refund operations
 * - Cache invalidation: explicit invalidation after state changes
 */
@Service
public class PaymentCacheService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCacheService.class);
    private static final String PAYMENT_CACHE_PREFIX = "payment:";
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutes

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PaymentCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Get payment from cache (with fallback to supplier if not found)
     */
    public Optional<Payment> getPayment(Long paymentId) {
        String key = cacheKey(paymentId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                Payment payment = objectMapper.readValue(cached, Payment.class);
                log.debug("Payment cache hit: id={}", paymentId);
                return Optional.of(payment);
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize payment from cache: id={}", paymentId, ex);
            invalidate(paymentId);
        } catch (DataAccessException ex) {
            log.warn("Redis access failed when reading payment cache: id={}", paymentId, ex);
        }
        return Optional.empty();
    }

    /**
     * Store payment in cache
     */
    public void cachePayment(Payment payment) {
        String key = cacheKey(payment.getId());
        try {
            String serialized = objectMapper.writeValueAsString(payment);
            redisTemplate.opsForValue().set(key, serialized, Duration.ofSeconds(CACHE_TTL_SECONDS));
            log.debug("Payment cached: id={}", payment.getId());
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize payment for cache: id={}", payment.getId(), ex);
        } catch (DataAccessException ex) {
            log.warn("Redis access failed when caching payment: id={}", payment.getId(), ex);
        }
    }

    /**
     * Invalidate payment cache (after status change)
     */
    public void invalidate(Long paymentId) {
        String key = cacheKey(paymentId);
        try {
            redisTemplate.delete(key);
            log.debug("Payment cache invalidated: id={}", paymentId);
        } catch (DataAccessException ex) {
            log.warn("Redis access failed when invalidating cache: id={}", paymentId, ex);
        }
    }

    private String cacheKey(Long paymentId) {
        return PAYMENT_CACHE_PREFIX + paymentId;
    }
}
