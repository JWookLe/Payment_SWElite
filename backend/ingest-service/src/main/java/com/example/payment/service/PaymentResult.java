package com.example.payment.service;

import com.example.payment.web.dto.PaymentResponse;

public record PaymentResult(PaymentResponse response, boolean duplicate) {
}
