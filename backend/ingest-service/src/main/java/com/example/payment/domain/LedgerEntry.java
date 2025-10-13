package com.example.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entry", indexes = {
        @Index(name = "ix_payment_time", columnList = "payment_id, occurred_at")
})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "debit_account", nullable = false, length = 64)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false, length = 64)
    private String creditAccount;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected LedgerEntry() {
    }

    public LedgerEntry(Payment payment, String debitAccount, String creditAccount, Long amount) {
        this.payment = payment;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
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
