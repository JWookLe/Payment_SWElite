package com.example.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idem_response_cache")
public class IdemResponseCache {

    @EmbeddedId
    private IdemResponseCacheId id;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "JSON")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected IdemResponseCache() {
    }

    public IdemResponseCache(String merchantId, String idempotencyKey, int httpStatus, String responseBody) {
        this.id = new IdemResponseCacheId(merchantId, idempotencyKey);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public IdemResponseCacheId getId() {
        return id;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
