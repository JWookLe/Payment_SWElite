package com.example.payment.service;

public class RateLimitExceededException extends RuntimeException {

    private final String code;
    private final String merchantId;

    public RateLimitExceededException(String message, String code, String merchantId) {
        super(message);
        this.code = code;
        this.merchantId = merchantId;
    }

    public String getCode() {
        return code;
    }

    public String getMerchantId() {
        return merchantId;
    }
}
