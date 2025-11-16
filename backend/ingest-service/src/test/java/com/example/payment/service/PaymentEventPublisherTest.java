package com.example.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Consumer;

/**
 * PaymentEventPublisher 테스트
 *
 * Circuit Breaker로 보호된 이벤트 발행 로직을 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEventPublisher Tests")
class PaymentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private TransactionTemplate transactionTemplate;

    private PaymentEventPublisher publisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        var executor = new SyncTaskExecutor();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        publisher = new PaymentEventPublisher(
                kafkaTemplate,
                outboxEventRepository,
                objectMapper,
                circuitBreakerRegistry,
                executor,
                transactionTemplate
        );
    }

    @Test
    @DisplayName("승인 이벤트 발행 시 OutboxEvent가 저장되어야 함")
    void shouldSaveOutboxEventWhenPublishingAuthorizeEvent() {
        // Given
        Long paymentId = 1L;
        String eventType = "PAYMENT_AUTHORIZED";
        Map<String, Object> payload = Map.of(
                "paymentId", paymentId,
                "status", "REQUESTED",
                "amount", 50000L
        );

        OutboxEvent savedEvent = new OutboxEvent("payment", paymentId, eventType, "{}");
        when(outboxEventRepository.save(any())).thenReturn(savedEvent);

        // When
        publisher.publishEvent(paymentId, eventType, payload);

        // Then - OutboxEvent 저장이 호출되어야 함
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("정산 이벤트 발행 시 OutboxEvent가 저장되어야 함")
    void shouldSaveOutboxEventWhenPublishingCaptureEvent() {
        // Given
        Long paymentId = 2L;
        String eventType = "PAYMENT_CAPTURED";
        Map<String, Object> payload = Map.of("paymentId", paymentId, "status", "COMPLETED");

        OutboxEvent savedEvent = new OutboxEvent("payment", paymentId, eventType, "{}");
        when(outboxEventRepository.save(any())).thenReturn(savedEvent);

        // When
        publisher.publishEvent(paymentId, eventType, payload);

        // Then
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("환불 이벤트 발행 시 OutboxEvent가 저장되어야 함")
    void shouldSaveOutboxEventWhenPublishingRefundEvent() {
        // Given
        Long paymentId = 3L;
        String eventType = "PAYMENT_REFUNDED";
        Map<String, Object> payload = Map.of("paymentId", paymentId, "status", "REFUNDED", "reason", "customer_request");

        OutboxEvent savedEvent = new OutboxEvent("payment", paymentId, eventType, "{}");
        when(outboxEventRepository.save(any())).thenReturn(savedEvent);

        // When
        publisher.publishEvent(paymentId, eventType, payload);

        // Then
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("빈 payload로도 안전하게 처리되어야 함")
    void shouldHandleEmptyPayloadSafely() {
        // Given
        Long paymentId = 4L;
        String eventType = "PAYMENT_AUTHORIZED";

        OutboxEvent savedEvent = new OutboxEvent("payment", paymentId, eventType, "{}");
        when(outboxEventRepository.save(any())).thenReturn(savedEvent);

        // When & Then - 예외 발생 없이 정상 처리
        assertDoesNotThrow(() -> {
            publisher.publishEvent(paymentId, eventType, Map.of());
        });

        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("여러 이벤트를 동시에 발행해도 안전해야 함")
    void shouldHandleConcurrentPublishingSafely() throws InterruptedException {
        // Given
        OutboxEvent savedEvent = new OutboxEvent("payment", 1L, "PAYMENT_AUTHORIZED", "{}");
        when(outboxEventRepository.save(any())).thenReturn(savedEvent);

        // When - 10개 스레드에서 동시 발행
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                publisher.publishEvent((long) index, "PAYMENT_AUTHORIZED", Map.of("paymentId", index));
            });
            threads[i].start();
        }

        // 모든 스레드 완료 대기
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - 10번 저장되었는지 검증
        verify(outboxEventRepository, times(10)).save(any(OutboxEvent.class));
    }
}
