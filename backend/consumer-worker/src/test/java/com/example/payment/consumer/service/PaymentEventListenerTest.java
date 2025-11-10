package com.example.payment.consumer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.consumer.repository.LedgerEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private PaymentEventListener eventListener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventListener = new PaymentEventListener(
                ledgerEntryRepository,
                objectMapper,
                kafkaTemplate,
                "payment.dlq"
        );

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(future);
    }

    @Test
    void sendsToDlqWhenPayloadCannotBeParsed() {
        String invalidPayload = "invalid-json";

        assertDoesNotThrow(() ->
                eventListener.handleEvent(new ConsumerRecord<>("payment.captured", 0, 10L, null, invalidPayload))
        );

        verify(kafkaTemplate).send(anyString(), anyString());
        verify(ledgerEntryRepository, never())
                .existsByPaymentIdAndDebitAccountAndCreditAccount(anyLong(), anyString(), anyString());
    }

    @Test
    void sendsToDlqWhenLedgerUpdateFails() throws Exception {
        when(ledgerEntryRepository.existsByPaymentIdAndDebitAccountAndCreditAccount(anyLong(), anyString(), anyString()))
                .thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenThrow(new RuntimeException("DB failure"));

        String payload = objectMapper.writeValueAsString(Map.of(
                "paymentId", 42,
                "amount", 1000,
                "occurredAt", Instant.now().toString()
        ));

        assertDoesNotThrow(() ->
                eventListener.handleEvent(new ConsumerRecord<>("payment.captured", 1, 11L, null, payload))
        );

        verify(ledgerEntryRepository).existsByPaymentIdAndDebitAccountAndCreditAccount(anyLong(), anyString(), anyString());
        verify(ledgerEntryRepository).save(any());
        verify(kafkaTemplate, atLeastOnce()).send(anyString(), anyString());
    }
}
