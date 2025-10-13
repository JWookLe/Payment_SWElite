package com.example.payment.consumer.repository;

import com.example.payment.consumer.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
