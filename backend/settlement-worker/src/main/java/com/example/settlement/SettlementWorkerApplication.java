package com.example.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settlement Worker Application
 *
 * 역할: 정산 요청 이벤트를 처리하는 독립 워커
 * - payment.capture-requested 토픽 구독
 * - Mock PG API 호출 (정산 처리 시뮬레이션)
 * - 성공 시: payment.captured 이벤트 발행
 * - 실패 시: 재시도 또는 DLQ 처리
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
@EnableDiscoveryClient
public class SettlementWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementWorkerApplication.class, args);
    }
}
