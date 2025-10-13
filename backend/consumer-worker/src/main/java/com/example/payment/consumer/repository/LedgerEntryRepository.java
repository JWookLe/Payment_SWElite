package com.example.payment.consumer.repository;

import com.example.payment.consumer.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    boolean existsByPaymentIdAndDebitAccountAndCreditAccount(Long paymentId, String debitAccount, String creditAccount);
}
