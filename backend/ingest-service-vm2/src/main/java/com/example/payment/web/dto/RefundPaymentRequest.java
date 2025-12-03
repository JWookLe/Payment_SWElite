package com.example.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundPaymentRequest(
        @NotBlank @Size(max = 32) String merchantId,
        @NotBlank @Size(max = 128) String reason
) {
}
