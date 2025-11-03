package com.example.refund.scheduler;

import com.example.refund.client.MockPgApiClient;
import com.example.refund.domain.Payment;
import com.example.refund.domain.PaymentStatus;
import com.example.refund.domain.RefundRequest;
import com.example.refund.repository.PaymentRepository;
import com.example.refund.repository.RefundRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 환불 재시도 스케줄러
 */
@Component
public class RefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryScheduler.class);

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentRepository paymentRepository;
    private final MockPgApiClient pgApiClient;

    @Value("${refund.max-retries:10}")
    private int maxRetries;

    @Value("${refund.retry-interval-seconds:30}")
    private int retryIntervalSeconds;

    public RefundRetryScheduler(RefundRequestRepository refundRequestRepository,
                                PaymentRepository paymentRepository,
                                MockPgApiClient pgApiClient) {
        this.refundRequestRepository = refundRequestRepository;
        this.paymentRepository = paymentRepository;
        this.pgApiClient = pgApiClient;
    }

    @Scheduled(fixedDelayString = "${refund.retry-scheduler.interval-ms:10000}",
               initialDelayString = "${refund.retry-scheduler.initial-delay-ms:30000}")
    @Transactional
    public void retryFailedRefunds() {
        Instant retryThreshold = Instant.now().minus(retryIntervalSeconds, ChronoUnit.SECONDS);

        List<RefundRequest> failedRequests = refundRequestRepository
                .findByStatusAndRetryCountLessThanAndLastRetryAtBefore(
                        RefundRequest.RefundStatus.FAILED,
                        maxRetries,
                        retryThreshold
                );

        if (failedRequests.isEmpty()) {
            return;
        }

        log.info("Found {} failed refund requests to retry", failedRequests.size());

        for (RefundRequest request : failedRequests) {
            try {
                request.incrementRetryCount();
                refundRequestRepository.save(request);

                Payment payment = paymentRepository.findById(request.getPaymentId())
                        .orElseThrow(() -> new IllegalStateException("Payment not found"));

                MockPgApiClient.RefundResponse response = pgApiClient.requestRefund(
                        payment.getId(),
                        request.getRefundAmount(),
                        request.getRefundReason()
                );

                request.markSuccess(response.getCancelTransactionId(), response.getResponseCode(), response.getResponseMessage());
                payment.setStatus(PaymentStatus.REFUNDED);

                paymentRepository.save(payment);
                refundRequestRepository.save(request);

                log.info("Refund retry succeeded: paymentId={}, attemptCount={}", payment.getId(), request.getRetryCount());

            } catch (Exception ex) {
                log.warn("Refund retry failed: paymentId={}, attemptCount={}", request.getPaymentId(), request.getRetryCount());
            }
        }
    }

    @Scheduled(fixedDelayString = "${refund.dead-letter.check-interval-ms:300000}")
    public void checkDeadLetterRefunds() {
        List<RefundRequest> deadLetters = refundRequestRepository
                .findByStatusAndRetryCountGreaterThanEqual(
                        RefundRequest.RefundStatus.FAILED,
                        maxRetries
                );

        if (!deadLetters.isEmpty()) {
            log.error("Found {} refund dead letter requests", deadLetters.size());
        }
    }
}
