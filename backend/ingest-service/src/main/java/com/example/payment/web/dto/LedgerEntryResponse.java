package com.example.payment.web.dto;

public record LedgerEntryResponse(
        String debitAccount,
        String creditAccount,
        Long amount
) {
}
