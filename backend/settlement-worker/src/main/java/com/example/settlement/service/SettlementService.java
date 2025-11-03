package com.example.settlement.service;

import com.example.settlement.client.MockPgApiClient;
import com.example.settlement.client.MockPgApiClient.PgApiException;
import com.example.settlement.client.MockPgApiClient.SettlementResponse;
import com.example.settlement.domain.Payment;
import com.example.settlement.domain.PaymentStatus;
import com.example.settlement.domain.SettlementRequest;
import com.example.settlement.domain.SettlementRequest.SettlementStatus;
import com.example.settlement.repository.PaymentRepository;
import com.example.settlement.repository.SettlementRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 정산 서비스
 * PG API를 호출하여 매입 확정 처리
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRequestRepository settlementRequestRepository;
    private final PaymentRepository paymentRepository;
    private final MockPgApiClient pgApiClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SettlementService(SettlementRequestRepository settlementRequestRepository,
                             PaymentRepository paymentRepository,
                             MockPgApiClient pgApiClient,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.settlementRequestRepository = settlementRequestRepository;
        this.paymentRepository = paymentRepository;
        this.pgApiClient = pgApiClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 정산 처리
     * 1. SettlementRequest 생성
     * 2. Mock PG API 호출
     * 3. 성공 시: Payment 상태 CAPTURED 변경 + payment.captured 이벤트 발행
     * 4. 실패 시: SettlementRequest FAILED 상태로 변경
     */
    @Transactional
    public void processSettlement(Long paymentId, Long amount) {
        log.info("Processing settlement: paymentId={}, amount={}", paymentId, amount);

        // Payment 조회
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        // 이미 정산 요청이 있는지 확인
        SettlementRequest existingRequest = settlementRequestRepository.findByPaymentId(paymentId)
                .orElse(null);

        SettlementRequest settlementRequest;
        if (existingRequest != null && existingRequest.getStatus() == SettlementStatus.SUCCESS) {
            log.info("Settlement already processed: paymentId={}", paymentId);
            return;
        } else if (existingRequest != null) {
            // 실패 건 재시도
            settlementRequest = existingRequest;
            settlementRequest.incrementRetryCount();
        } else {
            // 새로운 정산 요청
            settlementRequest = new SettlementRequest(paymentId, BigDecimal.valueOf(amount));
        }

        settlementRequestRepository.save(settlementRequest);

        try {
            // Mock PG API 호출
            SettlementResponse response = pgApiClient.requestSettlement(
                    paymentId,
                    BigDecimal.valueOf(amount)
            );

            // 정산 성공 처리
            settlementRequest.markSuccess(
                    response.getTransactionId(),
                    response.getResponseCode(),
                    response.getResponseMessage()
            );
            settlementRequestRepository.save(settlementRequest);

            // Payment 상태 업데이트
            payment.setStatus(PaymentStatus.CAPTURED);
            paymentRepository.save(payment);

            // payment.captured 이벤트 발행 (consumer-worker가 ledger 기록)
            publishCapturedEvent(payment);

            log.info("Settlement succeeded: paymentId={}, txnId={}", paymentId, response.getTransactionId());

        } catch (PgApiException ex) {
            // 정산 실패 처리
            settlementRequest.markFailed(ex.getErrorCode(), ex.getMessage());
            settlementRequestRepository.save(settlementRequest);

            // Payment 상태 업데이트
            payment.setStatus(PaymentStatus.CAPTURE_FAILED);
            paymentRepository.save(payment);

            log.error("Settlement failed: paymentId={}, error={}", paymentId, ex.getMessage());

            // 재시도 가능한지 확인
            if (!settlementRequest.canRetry(10)) {
                log.error("Settlement max retries exceeded: paymentId={}", paymentId);
                // DLQ로 전송하거나 알림 발송
            }
        }
    }

    /**
     * payment.captured 이벤트 발행 (public - 스케줄러에서 사용)
     */
    public void publishCapturedEvent(Payment payment, Long amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", payment.getId());
        payload.put("status", payment.getStatus().name());
        payload.put("amount", amount != null ? amount : payment.getAmount());
        payload.put("occurredAt", Instant.now().toString());

        kafkaTemplate.send("payment.captured", payment.getId().toString(), payload);
        log.info("Published payment.captured event: paymentId={}", payment.getId());
    }

    /**
     * payment.captured 이벤트 발행 (private - 내부용)
     */
    private void publishCapturedEvent(Payment payment) {
        publishCapturedEvent(payment, payment.getAmount());
    }
}
