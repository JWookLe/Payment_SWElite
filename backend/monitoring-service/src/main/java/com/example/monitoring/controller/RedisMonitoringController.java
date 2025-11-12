package com.example.monitoring.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * REST API for Redis Cache and Rate Limiter monitoring
 * Provides same functionality as redis-cache-mcp but via HTTP
 */
@RestController
@RequestMapping("/monitoring/redis")
public class RedisMonitoringController {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * GET /monitoring/redis/rate-limit?merchantId=merchant123
     * Check rate limit status for a merchant
     */
    @GetMapping("/rate-limit")
    public ResponseEntity<Map<String, Object>> checkRateLimit(
            @RequestParam String merchantId) {

        try {
            String key = "rate_limit:" + merchantId;
            String value = redisTemplate.opsForValue().get(key);
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

            if (value == null) {
                return ResponseEntity.ok(Map.of(
                        "merchantId", merchantId,
                        "currentCount", 0,
                        "limit", 100,
                        "remaining", 100,
                        "status", "OK",
                        "message", "No requests recorded in current window"
                ));
            }

            int currentCount = Integer.parseInt(value);
            int limit = 100;
            int remaining = Math.max(0, limit - currentCount);
            boolean isBlocked = currentCount >= limit;

            return ResponseEntity.ok(Map.of(
                    "merchantId", merchantId,
                    "currentCount", currentCount,
                    "limit", limit,
                    "remaining", remaining,
                    "ttlSeconds", ttl != null ? ttl : 0,
                    "status", isBlocked ? "RATE_LIMITED" : "OK",
                    "message", isBlocked
                        ? "Rate limit exceeded. Wait " + ttl + " seconds"
                        : "Rate limit OK"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Rate limit check failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/idempotency?key=abc123
     * Check if idempotency key exists
     */
    @GetMapping("/idempotency")
    public ResponseEntity<Map<String, Object>> checkIdempotency(
            @RequestParam String key) {

        try {
            String redisKey = "idempotency:" + key;
            String value = redisTemplate.opsForValue().get(redisKey);
            Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);

            if (value == null) {
                return ResponseEntity.ok(Map.of(
                        "exists", false,
                        "idempotencyKey", key,
                        "message", "No duplicate - this idempotency key is available"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "idempotencyKey", key,
                    "paymentId", value,
                    "ttlSeconds", ttl != null ? ttl : 0,
                    "message", "Duplicate detected - payment " + value + " already processed with this key"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Idempotency check failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/stats
     * Get comprehensive Redis statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRedisStats() {
        try {
            Set<String> rateLimitKeys = redisTemplate.keys("rate_limit:*");
            Set<String> idempotencyKeys = redisTemplate.keys("idempotency:*");
            Set<String> cacheKeys = redisTemplate.keys("cache:*");

            Map<String, Object> stats = new HashMap<>();
            stats.put("rateLimitKeys", rateLimitKeys != null ? rateLimitKeys.size() : 0);
            stats.put("idempotencyKeys", idempotencyKeys != null ? idempotencyKeys.size() : 0);
            stats.put("cacheKeys", cacheKeys != null ? cacheKeys.size() : 0);
            stats.put("totalKeys",
                (rateLimitKeys != null ? rateLimitKeys.size() : 0) +
                (idempotencyKeys != null ? idempotencyKeys.size() : 0) +
                (cacheKeys != null ? cacheKeys.size() : 0)
            );

            // Count blocked merchants
            long blockedCount = 0;
            if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
                for (String key : rateLimitKeys) {
                    String value = redisTemplate.opsForValue().get(key);
                    if (value != null && Integer.parseInt(value) >= 100) {
                        blockedCount++;
                    }
                }
            }
            stats.put("blockedMerchants", blockedCount);

            // Sample some rate limit values
            List<Map<String, Object>> rateLimitSamples = new ArrayList<>();
            if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
                rateLimitKeys.stream()
                        .limit(5)
                        .forEach(key -> {
                            String value = redisTemplate.opsForValue().get(key);
                            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                            rateLimitSamples.add(Map.of(
                                    "key", key,
                                    "count", value != null ? value : "0",
                                    "ttlSeconds", ttl != null ? ttl : 0
                            ));
                        });
            }

            stats.put("rateLimitSamples", rateLimitSamples);
            stats.put("message", "Redis statistics retrieved successfully");

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Redis stats retrieval failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/cache-stats
     * Get cache statistics
     */
    @GetMapping("/cache-stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        try {
            Set<String> rateLimitKeys = redisTemplate.keys("rate_limit:*");
            Set<String> idempotencyKeys = redisTemplate.keys("idempotency:*");
            Set<String> cacheKeys = redisTemplate.keys("cache:*");

            Map<String, Object> stats = new HashMap<>();
            stats.put("rateLimitKeys", rateLimitKeys != null ? rateLimitKeys.size() : 0);
            stats.put("idempotencyKeys", idempotencyKeys != null ? idempotencyKeys.size() : 0);
            stats.put("cacheKeys", cacheKeys != null ? cacheKeys.size() : 0);
            stats.put("totalKeys",
                (rateLimitKeys != null ? rateLimitKeys.size() : 0) +
                (idempotencyKeys != null ? idempotencyKeys.size() : 0) +
                (cacheKeys != null ? cacheKeys.size() : 0)
            );

            // Sample some rate limit values
            List<Map<String, Object>> rateLimitSamples = new ArrayList<>();
            if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
                rateLimitKeys.stream()
                        .limit(5)
                        .forEach(key -> {
                            String value = redisTemplate.opsForValue().get(key);
                            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                            rateLimitSamples.add(Map.of(
                                    "key", key,
                                    "count", value != null ? value : "0",
                                    "ttlSeconds", ttl != null ? ttl : 0
                            ));
                        });
            }

            stats.put("rateLimitSamples", rateLimitSamples);
            stats.put("message", "Redis cache statistics retrieved successfully");

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Cache stats retrieval failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * DELETE /monitoring/redis/rate-limit?merchantId=merchant123
     * Clear rate limit for a merchant (admin only)
     */
    @DeleteMapping("/rate-limit")
    public ResponseEntity<Map<String, Object>> clearRateLimit(
            @RequestParam String merchantId) {

        try {
            String key = "rate_limit:" + merchantId;
            Boolean deleted = redisTemplate.delete(key);

            return ResponseEntity.ok(Map.of(
                    "success", deleted != null && deleted,
                    "merchantId", merchantId,
                    "message", deleted != null && deleted
                        ? "Rate limit cleared successfully"
                        : "No rate limit found for this merchant"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Rate limit clear failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/rate-limit/all
     * List all merchants with rate limits
     */
    @GetMapping("/rate-limit/all")
    public ResponseEntity<Map<String, Object>> listAllRateLimits() {
        try {
            Set<String> keys = redisTemplate.keys("rate_limit:*");

            if (keys == null || keys.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "count", 0,
                        "rateLimits", Collections.emptyList(),
                        "message", "No rate limits found"
                ));
            }

            List<Map<String, Object>> rateLimits = keys.stream()
                    .map(key -> {
                        String merchantId = key.replace("rate_limit:", "");
                        String value = redisTemplate.opsForValue().get(key);
                        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                        int count = value != null ? Integer.parseInt(value) : 0;
                        long ttlSeconds = ttl != null ? ttl : 0L;

                        Map<String, Object> result = new HashMap<>();
                        result.put("merchantId", merchantId);
                        result.put("currentCount", count);
                        result.put("ttlSeconds", ttlSeconds);
                        result.put("status", count >= 100 ? "RATE_LIMITED" : "OK");
                        return result;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "count", rateLimits.size(),
                    "rateLimits", rateLimits
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Rate limit listing failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/rate-limit/blocked
     * Find merchants currently blocked by rate limits
     */
    @GetMapping("/rate-limit/blocked")
    public ResponseEntity<Map<String, Object>> getBlockedMerchants() {
        try {
            Set<String> keys = redisTemplate.keys("rate_limit:*");

            if (keys == null || keys.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "count", 0,
                        "blockedMerchants", Collections.emptyList(),
                        "message", "No rate limits found"
                ));
            }

            List<Map<String, Object>> blocked = keys.stream()
                    .map(key -> {
                        String merchantId = key.replace("rate_limit:", "");
                        String value = redisTemplate.opsForValue().get(key);
                        int count = value != null ? Integer.parseInt(value) : 0;
                        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                        long ttlSeconds = ttl != null ? ttl : 0L;

                        Map<String, Object> result = new HashMap<>();
                        result.put("merchantId", merchantId);
                        result.put("count", count);
                        result.put("ttl", ttlSeconds);
                        result.put("isBlocked", count >= 100);
                        return result;
                    })
                    .filter(m -> (Boolean) m.get("isBlocked"))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "count", blocked.size(),
                    "blockedMerchants", blocked,
                    "message", blocked.isEmpty()
                        ? "No merchants currently blocked"
                        : blocked.size() + " merchant(s) are rate limited"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Blocked merchants check failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /monitoring/redis/ttl-analysis
     * Analyze TTL distribution across Redis keys
     */
    @GetMapping("/ttl-analysis")
    public ResponseEntity<Map<String, Object>> analyzeTTL() {
        try {
            Set<String> allKeys = redisTemplate.keys("*");

            if (allKeys == null || allKeys.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "totalKeys", 0,
                        "message", "No keys found in Redis"
                ));
            }

            Map<String, List<Long>> ttlByPrefix = new HashMap<>();
            Map<String, Integer> countByPrefix = new HashMap<>();

            for (String key : allKeys) {
                String prefix = key.split(":")[0];
                Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

                ttlByPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(ttl != null ? ttl : -1);
                countByPrefix.put(prefix, countByPrefix.getOrDefault(prefix, 0) + 1);
            }

            Map<String, Object> analysis = new HashMap<>();
            for (String prefix : ttlByPrefix.keySet()) {
                List<Long> ttls = ttlByPrefix.get(prefix);
                long avgTTL = (long) ttls.stream()
                        .filter(t -> t >= 0)
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0);

                long expiringSoon = ttls.stream().filter(t -> t >= 0 && t < 60).count();
                long neverExpire = ttls.stream().filter(t -> t == -1).count();

                analysis.put(prefix, Map.of(
                        "count", countByPrefix.get(prefix),
                        "avgTTLSeconds", avgTTL,
                        "expiringSoon", expiringSoon,
                        "neverExpire", neverExpire
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "totalKeys", allKeys.size(),
                    "analysis", analysis,
                    "message", "TTL analysis completed"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "TTL analysis failed",
                    "message", e.getMessage()
            ));
        }
    }
}
