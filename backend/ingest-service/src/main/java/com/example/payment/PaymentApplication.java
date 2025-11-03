package com.example.payment;

import com.example.payment.config.CircuitBreakerProperties;
import com.example.payment.config.IdempotencyCacheProperties;
import com.example.payment.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({RateLimitProperties.class, IdempotencyCacheProperties.class, CircuitBreakerProperties.class})
@EnableDiscoveryClient
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
