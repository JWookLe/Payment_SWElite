package com.example.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${payment.topics.authorized:payment.authorized}")
    private String authorizedTopic;

    @Value("${payment.topics.captured:payment.captured}")
    private String capturedTopic;

    @Value("${payment.topics.refunded:payment.refunded}")
    private String refundedTopic;

    @Value("${payment.topics.dlq:payment.dlq}")
    private String dlqTopic;

    @Bean
    public NewTopic paymentAuthorizedTopic() {
        return TopicBuilder.name(authorizedTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentCapturedTopic() {
        return TopicBuilder.name(capturedTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRefundedTopic() {
        return TopicBuilder.name(refundedTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentDeadLetterTopic() {
        return TopicBuilder.name(dlqTopic).partitions(1).replicas(1).build();
    }
}
