package com.example.payment.web.dto;

public record ErrorResponse(
        String code,
        String message,
        Long paymentId
) {
}
