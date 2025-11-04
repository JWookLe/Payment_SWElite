package com.example.settlement.scheduler;

import com.example.settlement.client.MockPgApiClient;
import com.example.settlement.domain.Payment;
import com.example.settlement.domain.PaymentStatus;
import com.example.settlement.domain.SettlementRequest;
import com.example.settlement.repository.PaymentRepository;
import com.example.settlement.repository.SettlementRequestRepository;
import com.example.settlement.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산 재시도 스케줄러
 * 실패한 정산 요청을 자동으로 재시도
 */
@Component
public class SettlementRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementRetryScheduler.class);

    private final SettlementRequestRepository settlementRequestRepository;
    private final PaymentRepository paymentRepository;
    private final MockPgApiClient pgApiClient;
    private final SettlementService settlementService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${settlement.max-retries:10}")
    private int maxRetries;

    @Value("${settlement.retry-interval-seconds:30}")
    private int retryIntervalSeconds;

    public SettlementRetryScheduler(SettlementRequestRepository settlementRequestRepository,
                                    PaymentRepository paymentRepository,
                                    MockPgApiClient pgApiClient,
                                    SettlementService settlementService,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper) {
        this.settlementRequestRepository = settlementRequestRepository;
        this.paymentRepository = paymentRepository;
        this.pgApiClient = pgApiClient;
        this.settlementService = settlementService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 10초마다 실패한 정산 재시도
     */
    @Scheduled(fixedDelayString = "${settlement.retry-scheduler.interval-ms:10000}",
               initialDelayString = "${settlement.retry-scheduler.initial-delay-ms:30000}")
    @Transactional
    public void retryFailedSettlements() {
        Instant retryThreshold = Instant.now().minus(retryIntervalSeconds, ChronoUnit.SECONDS);

        List<SettlementRequest> failedRequests = settlementRequestRepository
                .findByStatusAndRetryCountLessThanAndLastRetryAtBefore(
                        SettlementRequest.SettlementStatus.FAILED,
                        maxRetries,
                        retryThreshold
                );

        if (failedRequests.isEmpty()) {
            return;
        }

        log.info("Found {} failed settlement requests to retry", failedRequests.size());

        int succeeded = 0;
        int failed = 0;

        for (SettlementRequest request : failedRequests) {
            try {
                request.incrementRetryCount();
                settlementRequestRepository.save(request);

                Payment payment = paymentRepository.findById(request.getPaymentId())
                        .orElseThrow(() -> new IllegalStateException("Payment not found: " + request.getPaymentId()));

                // Mock PG API 재호출
                MockPgApiClient.SettlementResponse response = pgApiClient.requestSettlement(
                        payment.getId(),
                        request.getRequestAmount()
                );

                // 성공 처리
                request.markSuccess(response.getTransactionId(), response.getResponseCode(), response.getResponseMessage());
                payment.setStatus(PaymentStatus.CAPTURED);

                paymentRepository.save(payment);
                settlementRequestRepository.save(request);

                // payment.captured 이벤트 발행
                settlementService.publishCapturedEvent(payment, request.getRequestAmount().longValue());

                succeeded++;
                log.info("Settlement retry succeeded: paymentId={}, attemptCount={}", payment.getId(), request.getRetryCount());

            } catch (Exception ex) {
                failed++;
                log.warn("Settlement retry failed: paymentId={}, attemptCount={}, error={}",
                        request.getPaymentId(), request.getRetryCount(), ex.getMessage());

                if (request.getRetryCount() >= maxRetries) {
                    log.error("Settlement exceeded max retries: paymentId={}, maxRetries={}",
                            request.getPaymentId(), maxRetries);
                    publishToDeadLetterQueue(request, ex.getMessage());
                }
            }
        }

        log.info("Settlement retry completed: {} succeeded, {} failed", succeeded, failed);
    }

    /**
     * 5분마다 Dead Letter 확인
     */
    @Scheduled(fixedDelayString = "${settlement.dead-letter.check-interval-ms:300000}")
    public void checkDeadLetterSettlements() {
        List<SettlementRequest> deadLetters = settlementRequestRepository
                .findByStatusAndRetryCountGreaterThanEqual(
                        SettlementRequest.SettlementStatus.FAILED,
                        maxRetries
                );

        if (!deadLetters.isEmpty()) {
            log.error("Found {} settlement dead letter requests (exceeded max retries)", deadLetters.size());
            for (SettlementRequest request : deadLetters) {
                log.error("Dead letter: paymentId={}, retryCount={}, lastError={}",
                        request.getPaymentId(), request.getRetryCount(), request.getPgResponseMessage());
            }
        }
    }

    /**
     * Dead Letter Queue로 메시지 발행
     */
    private void publishToDeadLetterQueue(SettlementRequest request, String errorMessage) {
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("settlementRequestId", request.getId());
            dlqMessage.put("paymentId", request.getPaymentId());
            dlqMessage.put("requestAmount", request.getRequestAmount());
            dlqMessage.put("retryCount", request.getRetryCount());
            dlqMessage.put("status", request.getStatus().name());
            dlqMessage.put("pgResponseCode", request.getPgResponseCode());
            dlqMessage.put("pgResponseMessage", request.getPgResponseMessage());
            dlqMessage.put("errorMessage", errorMessage);
            dlqMessage.put("requestedAt", request.getRequestedAt().toString());
            dlqMessage.put("lastRetryAt", request.getLastRetryAt() != null ? request.getLastRetryAt().toString() : null);
            dlqMessage.put("timestamp", Instant.now().toString());

            String jsonMessage = objectMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send("settlement.dlq", String.valueOf(request.getPaymentId()), jsonMessage);

            log.info("Published settlement dead letter to Kafka: paymentId={}, retryCount={}",
                    request.getPaymentId(), request.getRetryCount());
        } catch (Exception e) {
            log.error("Failed to publish settlement dead letter to Kafka: paymentId={}, error={}",
                    request.getPaymentId(), e.getMessage());
        }
    }
}
