package com.example.payment.web.dto;

import java.time.Instant;
import java.util.List;

public record PaymentResponse(
        Long paymentId,
        String status,
        Long amount,
        String currency,
        Instant createdAt,
        List<LedgerEntryResponse> ledgerEntries,
        String message
) {
}
