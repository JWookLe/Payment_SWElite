package com.example.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.domain.Payment;
import com.example.payment.repository.LedgerEntryRepository;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.web.dto.AuthorizePaymentRequest;
import com.example.payment.web.dto.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private IdempotencyCacheService idempotencyCacheService;
    @Mock
    private RedisRateLimiter rateLimiter;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        paymentService = new PaymentService(
                paymentRepository,
                ledgerEntryRepository,
                outboxEventRepository,
                idempotencyCacheService,
                rateLimiter,
                kafkaTemplate,
                objectMapper
        );
    }

    @Test
    void authorizeReturnsCachedResponseWithoutTriggeringRateLimiter() {
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("M123", 1000L, "KRW", "key-1");
        PaymentResponse cachedResponse = new PaymentResponse(
                42L,
                "REQUESTED",
                1000L,
                "KRW",
                Instant.now(),
                List.of(),
                "cached"
        );

        when(idempotencyCacheService.findAuthorization("M123", "key-1"))
                .thenReturn(Optional.of(new PaymentResult(cachedResponse, true)));

        PaymentResult result = paymentService.authorize(request);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.response()).isEqualTo(cachedResponse);
        verify(rateLimiter, never()).verifyAuthorizeAllowed(anyString());
    }

    @Test
    void authorizePersistsAndCachesWhenMiss() {
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("M123", 1000L, "KRW", "key-2");
        when(idempotencyCacheService.findAuthorization("M123", "key-2"))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantIdAndIdempotencyKey("M123", "key-2"))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    ReflectionTestUtils.setField(payment, "id", 99L);
                    return payment;
                });
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(future);

        PaymentResult result = paymentService.authorize(request);

        assertThat(result.duplicate()).isFalse();
        verify(rateLimiter).verifyAuthorizeAllowed("M123");
        verify(idempotencyCacheService)
                .storeAuthorization(eq("M123"), eq("key-2"), eq(200), any(PaymentResponse.class));
        verify(kafkaTemplate).send(eq("payment.authorized"), anyString());
    }
}
