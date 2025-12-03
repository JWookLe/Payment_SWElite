package com.example.payment.service;

import com.example.payment.client.PgAuthApiService;
import com.example.payment.client.PgAuthApiService.PgCircuitOpenException;
import com.example.payment.client.MockPgAuthApiClient.AuthorizationResponse;
import com.example.payment.client.MockPgAuthApiClient.PgApiException;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
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
        private final PgAuthApiService pgAuthApiService;
        private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
        private final boolean loadTestMode;

        public PaymentService(PaymentRepository paymentRepository,
                        IdempotencyCacheService idempotencyCacheService,
                        RedisRateLimiter rateLimiter,
                        PaymentEventPublisher eventPublisher,
                        PgAuthApiService pgAuthApiService,
                        org.springframework.transaction.PlatformTransactionManager transactionManager,
                        @org.springframework.beans.factory.annotation.Value("${mock.pg.loadtest-mode:false}") boolean loadTestMode) {
                this.paymentRepository = paymentRepository;
                this.idempotencyCacheService = idempotencyCacheService;
                this.rateLimiter = rateLimiter;
                this.eventPublisher = eventPublisher;
                this.pgAuthApiService = pgAuthApiService;
                this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(
                                transactionManager);
                this.loadTestMode = loadTestMode;
        }

        /**
         * 결제 승인 (실제 PG사 구조)
         * READY → AUTHORIZED 상태 전환
         * PG API 호출 → 승인 성공 → AUTHORIZED 상태 저장
         * (정산은 별도 API로 분리, 자동 트리거 제거)
         */
        // @Transactional 제거: PG API 호출을 트랜잭션 범위 밖으로 분리
        public PaymentResult authorize(AuthorizePaymentRequest request) {
                if (loadTestMode) {
                        return createAuthorization(request, true);
                }
                // 샤딩은 Controller에서 트랜잭션 시작 전에 설정됨
                return idempotencyCacheService.findAuthorization(request.merchantId(), request.idempotencyKey())
                                .orElseGet(() -> createAuthorization(request, false));
        }

        /**
         * 정산 처리 (내부 사용)
         * settlement-worker가 호출
         * AUTHORIZED → CAPTURED 상태 전환
         */
        @Transactional
        public PaymentResult capture(Long paymentId, CapturePaymentRequest request) {
                // 샤딩은 Controller에서 트랜잭션 시작 전에 설정됨
                return captureInternal(paymentId, request);
        }

        private PaymentResult captureInternal(Long paymentId, CapturePaymentRequest request) {
                long methodStart = System.currentTimeMillis();
                rateLimiter.verifyCaptureAllowed(request.merchantId());

                long dbStart = System.currentTimeMillis();
                Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));
                long dbReadTime = System.currentTimeMillis() - dbStart;
                log.debug("Capture - DB read time: {}ms", dbReadTime);

                if (payment.getStatus() != PaymentStatus.AUTHORIZED
                                && payment.getStatus() != PaymentStatus.CAPTURE_REQUESTED) {
                        PaymentResponse response = toResponse(payment, Collections.emptyList(),
                                        "Payment is not in AUTHORIZED or CAPTURE_REQUESTED status");
                        return new PaymentResult(response, true);
                }

                // 정산 완료 상태로 변경
                payment.setStatus(PaymentStatus.CAPTURED);
                dbStart = System.currentTimeMillis();
                paymentRepository.save(payment);
                long dbUpdateTime = System.currentTimeMillis() - dbStart;
                log.debug("Capture - DB update time: {}ms", dbUpdateTime);

                // payment.captured 이벤트 발행 (ledger 기록 트리거)
                long evtStart = System.currentTimeMillis();
                publishEvent(payment, "PAYMENT_CAPTURED", Map.of(
                                "paymentId", payment.getId(),
                                "status", payment.getStatus().name(),
                                "amount", payment.getAmount(),
                                "occurredAt", Instant.now().toString()));
                long evtTime = System.currentTimeMillis() - evtStart;
                log.debug("Capture - Event publish time: {}ms", evtTime);

                PaymentResponse response = toResponse(payment, Collections.emptyList(),
                                "Payment captured successfully");

                long totalTime = System.currentTimeMillis() - methodStart;
                log.info("Capture complete: dbRead={}ms, dbUpdate={}ms, event={}ms, totalTime={}ms",
                                dbReadTime, dbUpdateTime, evtTime, totalTime);

                return new PaymentResult(response, false);
        }

        /**
         * 환불 요청 (실제 PG사 구조)
         * CAPTURED → REFUND_REQUESTED 상태 전환
         * 환불 요청 시 payment.refund-requested 이벤트 발행
         */
        @Transactional
        public PaymentResult refund(Long paymentId, RefundPaymentRequest request) {
                // 샤딩은 Controller에서 트랜잭션 시작 전에 설정됨
                return refundInternal(paymentId, request);
        }

        private PaymentResult refundInternal(Long paymentId, RefundPaymentRequest request) {
                long methodStart = System.currentTimeMillis();
                rateLimiter.verifyRefundAllowed(request.merchantId());

                long dbStart = System.currentTimeMillis();
                Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, request.merchantId())
                                .orElseThrow(() -> new IllegalArgumentException("Payment not found for merchant"));
                long dbReadTime = System.currentTimeMillis() - dbStart;
                log.debug("Refund - DB read time: {}ms", dbReadTime);

                if (payment.getStatus() != PaymentStatus.CAPTURED) {
                        PaymentResponse response = toResponse(payment, Collections.emptyList(),
                                        "Only captured payments can be refunded");
                        return new PaymentResult(response, true);
                }

                // 환불 요청 상태로 변경
                payment.setStatus(PaymentStatus.REFUND_REQUESTED);
                dbStart = System.currentTimeMillis();
                paymentRepository.save(payment);
                long dbUpdateTime = System.currentTimeMillis() - dbStart;
                log.debug("Refund - DB update time: {}ms", dbUpdateTime);

                // payment.refund-requested 이벤트 발행 (refund-worker 트리거)
                long evtStart = System.currentTimeMillis();
                publishEvent(payment, "PAYMENT_REFUND_REQUESTED", Map.of(
                                "paymentId", payment.getId(),
                                "merchantId", payment.getMerchantId(),
                                "status", payment.getStatus().name(),
                                "amount", payment.getAmount(),
                                "occurredAt", Instant.now().toString(),
                                "reason", request.reason()));
                long evtTime = System.currentTimeMillis() - evtStart;
                log.debug("Refund - Event publish time: {}ms", evtTime);

                PaymentResponse response = toResponse(payment, Collections.emptyList(),
                                "Refund requested successfully");

                long totalTime = System.currentTimeMillis() - methodStart;
                log.info("Refund complete: dbRead={}ms, dbUpdate={}ms, event={}ms, totalTime={}ms",
                                dbReadTime, dbUpdateTime, evtTime, totalTime);

                return new PaymentResult(response, false);
        }

        private PaymentResult createAuthorization(AuthorizePaymentRequest request, boolean fastPathLoadTest) {
                long methodStart = System.currentTimeMillis();

                if (!fastPathLoadTest) {
                        Payment existing = paymentRepository.findByMerchantIdAndIdempotencyKey(
                                        request.merchantId(), request.idempotencyKey()).orElse(null);
                        if (existing != null) {
                                PaymentResponse response = toResponse(existing, Collections.emptyList(),
                                                "Idempotency key already used");
                                return new PaymentResult(response, true);
                        }
                }

                rateLimiter.verifyAuthorizeAllowed(request.merchantId());

                try {
                        // Step 1: Mock PG API 호출 (카드 승인) - Circuit Breaker로 보호됨
                        long pgStart = System.currentTimeMillis();
                        log.debug("Calling Mock PG Authorization API: merchantId={}, amount={}, currency={}",
                                        request.merchantId(), request.amount(), request.currency());

                        AuthorizationResponse pgResponse = pgAuthApiService.requestAuthorization(
                                        request.merchantId(),
                                        java.math.BigDecimal.valueOf(request.amount()),
                                        request.currency(),
                                        "MOCK_CARD_NUMBER" // 실제론 request에서 받아야 함
                        );

                        long pgTime = System.currentTimeMillis() - pgStart;
                        log.debug("PG Authorization succeeded: approvalNumber={}, transactionId={}, elapsedMs={}",
                                        pgResponse.getApprovalNumber(), pgResponse.getTransactionId(), pgTime);

                        // Step 2 & 3: DB 저장 및 이벤트 발행 (트랜잭션 내에서 실행)
                        long txStart = System.currentTimeMillis();
                        PaymentResponse response = transactionTemplate.execute(status -> {
                                // OPTIMIZATION: Save directly as CAPTURE_REQUESTED to avoid extra UPDATE
                                // (Authorized -> Capture Requested transition happens immediately)
                                Payment payment = new Payment(request.merchantId(), request.amount(),
                                                request.currency(), PaymentStatus.CAPTURE_REQUESTED,
                                                request.idempotencyKey());

                                long dbStart = System.currentTimeMillis();
                                paymentRepository.save(payment);
                                long dbTime = System.currentTimeMillis() - dbStart;
                                log.debug("DB save time: {}ms", dbTime);

                                // Event 1: Payment Authorized (Fact)
                                long evt1Start = System.currentTimeMillis();
                                publishEvent(payment, "PAYMENT_AUTHORIZED", Map.of(
                                                "paymentId", payment.getId(),
                                                "status", "AUTHORIZED", // Event payload keeps original semantic status
                                                "amount", payment.getAmount(),
                                                "currency", payment.getCurrency(),
                                                "approvalNumber", pgResponse.getApprovalNumber(),
                                                "transactionId", pgResponse.getTransactionId(),
                                                "occurredAt", Instant.now().toString()));
                                long evt1Time = System.currentTimeMillis() - evt1Start;
                                log.debug("Event 1 publish time: {}ms", evt1Time);

                                // Event 2: Capture Requested (Fact)
                                long evt2Start = System.currentTimeMillis();
                                publishEvent(payment, "PAYMENT_CAPTURE_REQUESTED", Map.of(
                                                "paymentId", payment.getId(),
                                                "status", payment.getStatus().name(),
                                                "amount", payment.getAmount(),
                                                "currency", payment.getCurrency(),
                                                "merchantId", payment.getMerchantId(),
                                                "approvalNumber", pgResponse.getApprovalNumber(),
                                                "transactionId", pgResponse.getTransactionId(),
                                                "occurredAt", Instant.now().toString()));
                                long evt2Time = System.currentTimeMillis() - evt2Start;
                                log.debug("Event 2 publish time: {}ms", evt2Time);

                                PaymentResponse res = toResponse(payment, Collections.emptyList(),
                                                "Payment authorized and capture requested - Approval: "
                                                                + pgResponse.getApprovalNumber());

                                if (!fastPathLoadTest) {
                                        // Save Idempotency Response within the same transaction
                                        long cacheStart = System.currentTimeMillis();
                                        idempotencyCacheService.storeAuthorization(request.merchantId(),
                                                        request.idempotencyKey(), 200,
                                                        res);
                                        long cacheTime = System.currentTimeMillis() - cacheStart;
                                        log.debug("Idempotency cache save time: {}ms", cacheTime);
                                }

                                return res;
                        });

                        long txTime = System.currentTimeMillis() - txStart;
                        long totalTime = System.currentTimeMillis() - methodStart;
                        log.debug("Authorization complete: pgTime={}ms, txTime={}ms, totalTime={}ms",
                                        pgTime, txTime, totalTime);

                        return new PaymentResult(response, false);
                } catch (PgCircuitOpenException circuitEx) {
                        // Circuit Breaker OPEN - PG API 다운됨
                        log.error("PG Authorization Circuit Breaker OPEN: merchantId={}, amount={}",
                                        request.merchantId(), request.amount());
                        PaymentResponse response = new PaymentResponse(
                                        null,
                                        "SERVICE_UNAVAILABLE",
                                        request.amount(),
                                        request.currency(),
                                        Instant.now(),
                                        Collections.emptyList(),
                                        "PG service temporarily unavailable. Please try again later.");
                        return new PaymentResult(response, true);
                } catch (PgApiException pgEx) {
                        // PG API 호출 실패 (승인 거부, 타임아웃 등)
                        log.error("PG Authorization failed: errorCode={}, message={}", pgEx.getErrorCode(),
                                        pgEx.getMessage());
                        PaymentResponse response = new PaymentResponse(
                                        null,
                                        "FAILED",
                                        request.amount(),
                                        request.currency(),
                                        Instant.now(),
                                        Collections.emptyList(),
                                        "Authorization failed: " + pgEx.getMessage());
                        return new PaymentResult(response, true);
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
                                message);
        }

}
