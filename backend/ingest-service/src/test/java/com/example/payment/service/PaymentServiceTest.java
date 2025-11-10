package com.example.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.client.MockPgAuthApiClient.AuthorizationResponse;
import com.example.payment.client.PgAuthApiService;
import com.example.payment.domain.Payment;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.web.dto.AuthorizePaymentRequest;
import com.example.payment.web.dto.PaymentResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PaymentService 단위 테스트
 *
 * Circuit Breaker 구현 후 기존 기능이 정상 작동하는지 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Tests")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyCacheService idempotencyCacheService;

    @Mock
    private RedisRateLimiter rateLimiter;

    @Mock
    private PaymentEventPublisher eventPublisher;

    @Mock
    private PgAuthApiService pgAuthApiService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                idempotencyCacheService,
                rateLimiter,
                eventPublisher,
                pgAuthApiService
        );
    }

    @Test
    @DisplayName("캐시에 있는 응답을 반환하고 레이트 제한을 체크하지 않아야 함")
    void authorizeReturnsCachedResponseWithoutTriggeringRateLimiter() {
        // Given: 캐시에 저장된 응답
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

        // When: authorize 호출
        PaymentResult result = paymentService.authorize(request);

        // Then: 중복 응답이고 레이트 제한을 체크하지 않아야 함
        assertThat(result.duplicate()).isTrue();
        assertThat(result.response()).isEqualTo(cachedResponse);
        verify(rateLimiter, never()).verifyAuthorizeAllowed(anyString());
    }

    @Test
    @DisplayName("캐시 미스 시 PG API 호출 후 결제를 DB에 저장하고 이벤트를 발행해야 함")
    void authorizePersistsAndPublishesEventWhenCacheMiss() throws Exception {
        // Given: 캐시 미스
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("M123", 1000L, "KRW", "key-2");

        when(idempotencyCacheService.findAuthorization("M123", "key-2"))
                .thenReturn(Optional.empty());

        when(paymentRepository.findByMerchantIdAndIdempotencyKey("M123", "key-2"))
                .thenReturn(Optional.empty());

        // Mock PG API 승인 응답
        AuthorizationResponse pgResponse = new AuthorizationResponse(
                "SUCCESS",
                "txn_12345678",
                "APP12345678",
                "0000",
                "승인 성공",
                BigDecimal.valueOf(1000L),
                Instant.now()
        );
        when(pgAuthApiService.requestAuthorization(
                eq("M123"),
                eq(BigDecimal.valueOf(1000L)),
                eq("KRW"),
                anyString()
        )).thenReturn(pgResponse);

        // Payment 저장 시 ID를 설정하여 반환
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    ReflectionTestUtils.setField(payment, "id", 99L);  // 실제로는 auto-increment로 생성됨
                    return payment;
                });

        // When: authorize 호출
        PaymentResult result = paymentService.authorize(request);

        // Then: 중복이 아니고 PG API 호출 및 이벤트가 발행되어야 함
        assertThat(result.duplicate()).isFalse();
        verify(rateLimiter).verifyAuthorizeAllowed("M123");
        verify(pgAuthApiService).requestAuthorization(
                eq("M123"),
                eq(BigDecimal.valueOf(1000L)),
                eq("KRW"),
                anyString()
        );
        verify(idempotencyCacheService).storeAuthorization(eq("M123"), eq("key-2"), eq(200), any(PaymentResponse.class));

        // Circuit Breaker를 통해 이벤트가 발행되어야 함
        ArgumentCaptor<Long> paymentIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher, times(2)).publishEvent(paymentIdCaptor.capture(), eventTypeCaptor.capture(), any());

        assertThat(paymentIdCaptor.getAllValues()).containsExactly(99L, 99L);
        assertThat(eventTypeCaptor.getAllValues()).containsExactly("PAYMENT_AUTHORIZED", "PAYMENT_CAPTURE_REQUESTED");
    }
}
