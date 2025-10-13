package com.example.payment.repository;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    Optional<Payment> findByIdAndMerchantId(Long id, String merchantId);

    long countByStatus(PaymentStatus status);
}
