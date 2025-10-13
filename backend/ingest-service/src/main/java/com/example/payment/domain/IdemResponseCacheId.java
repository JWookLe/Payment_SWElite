package com.example.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdemResponseCacheId implements Serializable {

    @Column(name = "merchant_id", nullable = false, length = 32)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    protected IdemResponseCacheId() {
    }

    public IdemResponseCacheId(String merchantId, String idempotencyKey) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdemResponseCacheId that = (IdemResponseCacheId) o;
        return Objects.equals(merchantId, that.merchantId) &&
                Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(merchantId, idempotencyKey);
    }
}
