package com.example.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CapturePaymentRequest(
        @NotBlank @Size(max = 32) String merchantId
) {
}
