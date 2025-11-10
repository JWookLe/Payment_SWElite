package com.example.payment.client;

import com.example.payment.client.MockPgAuthApiClient.AuthorizationResponse;
import com.example.payment.client.MockPgAuthApiClient.PgApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * PG Authorization API Service with Circuit Breaker protection
 *
 * MockPgAuthApiClient를 Circuit Breaker로 보호하는 래퍼 서비스
 * PG API 장애 시 빠른 실패(fail-fast)로 시스템 보호
 */
@Service
public class PgAuthApiService {

    private static final Logger log = LoggerFactory.getLogger(PgAuthApiService.class);
    private static final String CIRCUIT_BREAKER_NAME = "pg-auth-api";

    private final MockPgAuthApiClient pgAuthApiClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PgAuthApiService(MockPgAuthApiClient pgAuthApiClient,
                            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.pgAuthApiClient = pgAuthApiClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;

        // Register event listeners for monitoring
        registerCircuitBreakerEventListeners();
    }

    /**
     * 카드 승인 요청 (Circuit Breaker로 보호)
     *
     * @param merchantId 가맹점 ID
     * @param amount 승인 금액
     * @param currency 통화
     * @param cardNumber 카드 번호
     * @return 승인 응답
     * @throws PgApiException PG API 오류 발생 시
     * @throws PgCircuitOpenException Circuit Breaker가 OPEN 상태일 때
     */
    public AuthorizationResponse requestAuthorization(
            String merchantId,
            BigDecimal amount,
            String currency,
            String cardNumber
    ) throws PgApiException, PgCircuitOpenException {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        try {
            return circuitBreaker.executeCheckedSupplier(() ->
                pgAuthApiClient.requestAuthorization(merchantId, amount, currency, cardNumber)
            );
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
            // Circuit Breaker가 OPEN 상태 - PG API가 다운된 것으로 판단
            log.error("Circuit Breaker OPEN - PG Authorization API is unavailable. merchantId={}, amount={}",
                    merchantId, amount);
            throw new PgCircuitOpenException("PG Authorization service is temporarily unavailable");
        } catch (PgApiException pgEx) {
            // PG API 비즈니스 예외 (승인 거부, 타임아웃 등)
            throw pgEx;
        } catch (Throwable throwable) {
            // 예상치 못한 오류
            log.error("Unexpected error during PG Authorization: merchantId={}, amount={}",
                    merchantId, amount, throwable);
            throw new PgApiException("UNEXPECTED_ERROR", "Unexpected error: " + throwable.getMessage());
        }
    }

    /**
     * Circuit Breaker 상태 전환 모니터링
     */
    private void registerCircuitBreakerEventListeners() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("PG Auth API Circuit Breaker state transition: {} -> {}",
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()))
                .onError(event ->
                        log.error("PG Auth API Circuit Breaker error recorded: {}",
                                event.getThrowable().getMessage()))
                .onSuccess(event ->
                        log.debug("PG Auth API Circuit Breaker success recorded"));
    }

    /**
     * Circuit Breaker OPEN 예외
     * PG API가 장애 상태로 판단되어 요청이 차단된 경우
     */
    public static class PgCircuitOpenException extends Exception {
        public PgCircuitOpenException(String message) {
            super(message);
        }
    }
}
