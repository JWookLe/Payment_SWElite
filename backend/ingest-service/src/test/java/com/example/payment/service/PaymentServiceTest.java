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

/**
 * PaymentService 단위 테스트
 *
 * 이 테스트 클래스는 2가지 시나리오를 검증한다:
 *
 * 1. authorizeReturnsCachedResponseWithoutTriggeringRateLimiter
 *    - 시나리오: 같은 요청이 두 번 들어옴
 *    - 예상 동작:
 *      ① 첫 번째 요청: 결제 승인 (DB 저장, Kafka 발행)
 *      ② 두 번째 요청: 캐시에서 첫 번째 응답 반환, 레이트 제한 체크 스킵
 *    - 검증:
 *      - 레이트 제한을 체크하지 않았는가? (verify never)
 *      - 중복 응답인가? (duplicate == true)
 *
 * 2. authorizePersistsAndCachesWhenMiss
 *    - 시나리오: 새로운 결제 요청 (캐시에 없음)
 *    - 예상 동작:
 *      ① 캐시 조회 → 없음 (MISS)
 *      ② DB 확인 → 없음
 *      ③ 레이트 제한 체크 (통과)
 *      ④ 새로운 결제 생성 + DB 저장
 *      ⑤ Redis + DB에 캐시 저장
 *      ⑥ Kafka에 이벤트 발행
 *    - 검증:
 *      - 레이트 제한을 체크했는가? (verify called)
 *      - 캐시에 저장했는가? (verify called)
 *      - Kafka에 발행했는가? (verify called)
 *      - 중복 아닌가? (duplicate == false)
 */
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
        // 테스트명: 캐시에 있는 응답을 반환하고 레이트 제한을 체크하지 않는다

        // 요청 생성: 상인 M123이 1000 KRW 승인 요청 (idempotencyKey: key-1)
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("M123", 1000L, "KRW", "key-1");

        // 캐시에 저장되어 있는 응답 생성 (이미 처리된 요청의 결과)
        PaymentResponse cachedResponse = new PaymentResponse(
                42L,                          // 결제 ID
                "REQUESTED",                  // 결제 상태
                1000L,                        // 금액
                "KRW",                        // 통화
                Instant.now(),                // 생성 시간
                List.of(),                    // 복식부기 기록
                "cached"                      // 메시지
        );

        // Mock 설정: 캐시 조회 시 위의 응답을 반환하라 (캐시 HIT)
        when(idempotencyCacheService.findAuthorization("M123", "key-1"))
                .thenReturn(Optional.of(new PaymentResult(cachedResponse, true)));

        // 테스트 실행: authorize() 호출
        PaymentResult result = paymentService.authorize(request);

        // 검증 1: 중복 요청이어야 함 (duplicate == true)
        assertThat(result.duplicate()).isTrue();

        // 검증 2: 반환된 응답이 캐시된 응답과 정확히 같아야 함
        assertThat(result.response()).isEqualTo(cachedResponse);

        // 검증 3: 레이트 제한을 체크하지 않았어야 함 (캐시 HIT이므로 레이트 제한 스킵)
        verify(rateLimiter, never()).verifyAuthorizeAllowed(anyString());
    }

    @Test
    void authorizePersistsAndCachesWhenMiss() {
        // 테스트명: 캐시 미스 시 결제를 DB에 저장하고 캐시와 Kafka에 발행한다

        // 요청 생성: 상인 M123이 1000 KRW 승인 요청 (idempotencyKey: key-2)
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("M123", 1000L, "KRW", "key-2");

        // Mock 설정: 캐시 조회 → 없음 (캐시 MISS)
        // 실제로는 Redis + DB를 조회하지만, 여기서는 empty를 반환하라고 설정
        when(idempotencyCacheService.findAuthorization("M123", "key-2"))
                .thenReturn(Optional.empty());

        // Mock 설정: DB에서 existing 조회 → 없음 (새로운 결제)
        // 이 요청이 처음 들어오는 것이므로 DB에도 없어야 함
        when(paymentRepository.findByMerchantIdAndIdempotencyKey("M123", "key-2"))
                .thenReturn(Optional.empty());

        // Mock 설정: DB 저장 시 자동으로 ID를 설정 (마치 auto-increment처럼)
        // 실제로는 MariaDB가 1,2,3... 자동으로 증가시키지만
        // 테스트에서는 Payment 객체에 id=99를 설정해서 반환
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);  // 저장하려는 객체 받기
                    ReflectionTestUtils.setField(payment, "id", 99L);  // ID를 99로 설정
                    return payment;  // 수정된 객체 반환
                });

        // Mock 설정: OutboxEvent 저장 (복잡한 처리 없이 그냥 반환)
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock 설정: Kafka 전송 → 즉시 완료 상태로 반환
        // 실제로는 Kafka 브로커에 전송하지만, 테스트에서는 즉시 완료된 Future 반환
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(null);  // 완료 상태로 설정
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(future);

        // 테스트 실행: authorize() 호출
        PaymentResult result = paymentService.authorize(request);

        // 검증 1: 중복 아닌가? (첫 저장이므로 duplicate == false)
        assertThat(result.duplicate()).isFalse();

        // 검증 2: 레이트 제한을 체크했는가?
        // Mock의 verifyAuthorizeAllowed() 메서드가 호출되었는지 확인
        verify(rateLimiter).verifyAuthorizeAllowed("M123");

        // 검증 3: 캐시에 저장했는가?
        // storeAuthorization(merchantId, idempotencyKey, httpStatus, response)
        // - eq("M123"): 정확히 "M123"이어야 함
        // - eq("key-2"): 정확히 "key-2"이어야 함
        // - eq(200): 정확히 200 (HTTP OK 상태)이어야 함
        // - any(PaymentResponse.class): 어떤 PaymentResponse든 상관없음
        verify(idempotencyCacheService)
                .storeAuthorization(eq("M123"), eq("key-2"), eq(200), any(PaymentResponse.class));

        // 검증 4: Kafka에 발행했는가?
        // kafkaTemplate.send() 메서드가 호출되었는지 확인
        // - eq("payment.authorized"): 정확히 이 토픽 이름
        // - anyString(): 어떤 메시지든 상관없음
        verify(kafkaTemplate).send(eq("payment.authorized"), anyString());
    }
}
