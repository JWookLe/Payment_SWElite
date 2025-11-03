package com.example.payment.service;

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
    private final IdempotencyCacheService idempotencyCacheService;
    private final RedisRateLimiter rateLimiter;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          IdempotencyCacheService idempotencyCacheService,
                          RedisRateLimiter rateLimiter,
                          PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        // ledgerEntryRepository는 consumer-worker에서 처리 (비동기)
        this.idempotencyCacheService = idempotencyCacheService;
        this.rateLimiter = rateLimiter;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 결제 승인 (실제 PG사 구조)
     * READY → AUTHORIZED 상태 전환
     * 승인 성공 시 payment.capture-requested 이벤트 자동 발행
     */
    @Transactional
    public PaymentResult authorize(AuthorizePaymentRequest request) {
        return idempotencyCacheService.findAuthorization(request.merchantId(), request.idempotencyKey())
                .orElseGet(() -> createAuthorization(request));
    }

    /**
     * 정산 처리 (내부 사용)
     * settlement-worker가 호출
     * AUTHORIZED → CAPTURED 상태 전환
     */
    @Transactional
    public PaymentResult capture(Long paymentId, CapturePaymentRequest request) {
        rateLimiter.verifyCaptureAllowed(request.merchantId());

        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));

        if (payment.getStatus() != PaymentStatus.AUTHORIZED && payment.getStatus() != PaymentStatus.CAPTURE_REQUESTED) {
            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Payment is not in AUTHORIZED or CAPTURE_REQUESTED status");
            return new PaymentResult(response, true);
        }

        // 정산 완료 상태로 변경
        payment.setStatus(PaymentStatus.CAPTURED);
        paymentRepository.save(payment);

        // payment.captured 이벤트 발행 (ledger 기록 트리거)
        publishEvent(payment, "PAYMENT_CAPTURED", Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus().name(),
                "amount", payment.getAmount(),
                "occurredAt", Instant.now().toString()
        ));

        PaymentResponse response = toResponse(payment, Collections.emptyList(),
                "Payment captured successfully");

        return new PaymentResult(response, false);
    }

    /**
     * 환불 요청 (실제 PG사 구조)
     * CAPTURED → REFUND_REQUESTED 상태 전환
     * 환불 요청 시 payment.refund-requested 이벤트 발행
     */
    @Transactional
    public PaymentResult refund(Long paymentId, RefundPaymentRequest request) {
        rateLimiter.verifyRefundAllowed(request.merchantId());

        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));

        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Only captured payments can be refunded");
            return new PaymentResult(response, true);
        }

        // 환불 요청 상태로 변경
        payment.setStatus(PaymentStatus.REFUND_REQUESTED);
        paymentRepository.save(payment);

        // payment.refund-requested 이벤트 발행 (refund-worker 트리거)
        publishEvent(payment, "PAYMENT_REFUND_REQUESTED", Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus().name(),
                "amount", payment.getAmount(),
                "occurredAt", Instant.now().toString(),
                "reason", request.reason()
        ));

        PaymentResponse response = toResponse(payment, Collections.emptyList(),
                "Refund requested successfully");

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
            // AUTHORIZED 상태로 즉시 저장 (승인 완료)
            Payment payment = new Payment(request.merchantId(), request.amount(),
                    request.currency(), PaymentStatus.AUTHORIZED, request.idempotencyKey());
            paymentRepository.save(payment);

            PaymentResponse response = toResponse(payment, Collections.emptyList(),
                    "Payment authorized successfully");

            idempotencyCacheService.storeAuthorization(request.merchantId(), request.idempotencyKey(), 200, response);

            // payment.capture-requested 이벤트 발행 (자동 정산 트리거)
            publishEvent(payment, "PAYMENT_CAPTURE_REQUESTED", Map.of(
                    "paymentId", payment.getId(),
                    "status", payment.getStatus().name(),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency(),
                    "occurredAt", Instant.now().toString()
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

}
