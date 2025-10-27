package com.example.payment.service;

import java.util.function.Predicate;
import org.springframework.kafka.KafkaException;
import org.springframework.stereotype.Component;

/**
 * Determines which exceptions should trigger circuit breaker.
 * Only Kafka-related failures are considered as real failures.
 * Other exceptions are not recorded as failures for circuit breaker purposes.
 */
@Component
public class KafkaPublishingFailurePredicate implements Predicate<Exception> {

    @Override
    public boolean test(Exception exception) {
        // 카프카 관련 예외만 circuit breaker 트리거
        return exception instanceof KafkaException
                || (exception.getCause() instanceof KafkaException)
                || exception.getMessage() != null && exception.getMessage().contains("Kafka");
    }
}
