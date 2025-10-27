package com.example.payment.service;

import com.example.payment.domain.LedgerEntry;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.web.dto.AuthorizePaymentRequest;
import com.example.payment.web.dto.CapturePaymentRequest;
import com.example.payment.web.dto.LedgerEntryResponse;
import com.example.payment.web.dto.PaymentResponse;
import com.example.payment.web.dto.RefundPaymentRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyCacheService idempotencyCacheService;
    private final RedisRateLimiter rateLimiter;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          IdempotencyCacheService idempotencyCacheService,
                          RedisRateLimiter rateLimiter,
                          PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyCacheService = idempotencyCacheService;
        this.rateLimiter = rateLimiter;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PaymentResult authorize(AuthorizePaymentRequest request) {
        return idempotencyCacheService.findAuthorization(request.merchantId(), request.idempotencyKey())
                .orElseGet(() -> createAuthorization(request));
    }

    @Transactional
    public PaymentResult capture(Long paymentId, CapturePaymentRequest request) {
        rateLimiter.verifyCaptureAllowed(request.merchantId());

        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));

        if (payment.getStatus() != PaymentStatus.REQUESTED) {
            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Payment is not in REQUESTED status");
            return new PaymentResult(response, true);
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(payment,
                "merchant_receivable", "cash", payment.getAmount()));

        PaymentResponse response = toResponse(payment,
                List.of(toLedgerResponse(entry)),
                "Payment captured successfully");

        publishEvent(payment, "PAYMENT_CAPTURED", Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus().name(),
                "amount", payment.getAmount(),
                "occurredAt", Instant.now().toString()
        ));

        return new PaymentResult(response, false);
    }

    @Transactional
    public PaymentResult refund(Long paymentId, RefundPaymentRequest request) {
        rateLimiter.verifyRefundAllowed(request.merchantId());

        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Only completed payments can be refunded");
            return new PaymentResult(response, true);
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(payment,
                "cash", "merchant_receivable", payment.getAmount()));

        PaymentResponse response = toResponse(payment,
                List.of(toLedgerResponse(entry)),
                "Payment refunded successfully");

        publishEvent(payment, "PAYMENT_REFUNDED", Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus().name(),
                "amount", payment.getAmount(),
                "occurredAt", Instant.now().toString(),
                "reason", request.reason()
        ));

        return new PaymentResult(response, false);
    }

    private PaymentResult createAuthorization(AuthorizePaymentRequest request) {
        Payment existing = paymentRepository.findByMerchantIdAndIdempotencyKey(
                request.merchantId(), request.idempotencyKey()).orElse(null);
        if (existing != null) {
            PaymentResponse response = toResponse(existing, Collections.emptyList(),
                    "Idempotency key already used");
            return new PaymentResult(response, true);
        }

        rateLimiter.verifyAuthorizeAllowed(request.merchantId());

        try {
            Payment payment = new Payment(request.merchantId(), request.amount(),
                    request.currency(), PaymentStatus.REQUESTED, request.idempotencyKey());
            paymentRepository.save(payment);

            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Payment authorized successfully");

            idempotencyCacheService.storeAuthorization(request.merchantId(), request.idempotencyKey(), 200, response);

            publishEvent(payment, "PAYMENT_AUTHORIZED", Map.of(
                    "paymentId", payment.getId(),
                    "status", payment.getStatus().name(),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency()
            ));

            return new PaymentResult(response, false);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate idempotency key detected for merchant {}", request.merchantId());
            Payment payment = paymentRepository.findByMerchantIdAndIdempotencyKey(
                    request.merchantId(), request.idempotencyKey())
                    .orElseThrow(() -> ex);
            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Idempotency key already used");
            return new PaymentResult(response, true);
        }
    }

    private void publishEvent(Payment payment, String eventType, Map<String, Object> payload) {
        // Delegate to PaymentEventPublisher which handles circuit breaker logic
        eventPublisher.publishEvent(payment.getId(), eventType, payload);
    }

    private PaymentResponse toResponse(Payment payment, List<LedgerEntryResponse> ledgerEntries, String message) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getRequestedAt(),
                ledgerEntries,
                message
        );
    }

    private LedgerEntryResponse toLedgerResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(entry.getDebitAccount(), entry.getCreditAccount(), entry.getAmount());
    }
}
