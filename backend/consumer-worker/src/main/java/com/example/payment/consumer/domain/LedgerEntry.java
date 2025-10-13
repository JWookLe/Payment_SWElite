package com.example.payment.consumer.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "debit_account", nullable = false)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false)
    private String creditAccount;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long paymentId, String debitAccount, String creditAccount, Long amount, Instant occurredAt) {
        this.paymentId = paymentId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getDebitAccount() {
        return debitAccount;
    }

    public String getCreditAccount() {
        return creditAccount;
    }

    public Long getAmount() {
        return amount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
