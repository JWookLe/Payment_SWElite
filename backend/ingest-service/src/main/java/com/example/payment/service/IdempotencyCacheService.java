package com.example.payment.service;

import com.example.payment.config.IdempotencyCacheProperties;
import com.example.payment.domain.IdemResponseCache;
import com.example.payment.domain.IdemResponseCacheId;
import com.example.payment.repository.IdemResponseCacheRepository;
import com.example.payment.web.dto.PaymentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyCacheService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCacheService.class);

    private final IdemResponseCacheRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyCacheProperties properties;

    public IdempotencyCacheService(IdemResponseCacheRepository repository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            IdempotencyCacheProperties properties) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<PaymentResult> findAuthorization(String merchantId, String idempotencyKey) {
        String key = cacheKey(merchantId, idempotencyKey);
        try {
            String cachedBody = redisTemplate.opsForValue().get(key);
            if (cachedBody != null) {
                PaymentResponse response = objectMapper.readValue(cachedBody, PaymentResponse.class);
                return Optional.of(new PaymentResult(response, true));
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize idempotent response from Redis for merchant={}, key={}", merchantId,
                    idempotencyKey, ex);
        } catch (DataAccessException ex) {
            log.warn("Redis access failed when reading idempotent cache for merchant={}, key={}", merchantId,
                    idempotencyKey, ex);
        }

        return repository.findById(new IdemResponseCacheId(merchantId, idempotencyKey))
                .map(entity -> {
                    try {
                        PaymentResponse response = objectMapper.readValue(entity.getResponseBody(),
                                PaymentResponse.class);
                        putInRedis(key, entity.getResponseBody());
                        return new PaymentResult(response, true);
                    } catch (JsonProcessingException ex) {
                        throw new IllegalStateException(
                                String.format(Locale.ROOT,
                                        "Failed to deserialize cached response for merchant=%s key=%s",
                                        merchantId, idempotencyKey),
                                ex);
                    }
                });
    }

    @Transactional
    public void storeAuthorization(String merchantId, String idempotencyKey, int httpStatus, PaymentResponse response) {
        String serialized = serialize(response);
        try {
            repository.save(new IdemResponseCache(merchantId, idempotencyKey, httpStatus, serialized));
        } catch (DataIntegrityViolationException ex) {
            log.warn("Idempotent cache already exists for merchant={}, key={}", merchantId, idempotencyKey);
        }
        putInRedis(cacheKey(merchantId, idempotencyKey), serialized);
    }

    private String serialize(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotent response", ex);
        }
    }

    private void putInRedis(String key, String body) {
        try {
            if (properties.getTtlSeconds() > 0) {
                redisTemplate.opsForValue().set(key, body, Duration.ofSeconds(properties.getTtlSeconds()));
            } else {
                redisTemplate.opsForValue().set(key, body);
            }
        } catch (DataAccessException ex) {
            log.warn("Redis access failed when saving idempotent cache for key={}", key, ex);
        }
    }

    private String cacheKey(String merchantId, String idempotencyKey) {
        return "idem:authorize:" + merchantId + ":" + idempotencyKey;
    }
}
