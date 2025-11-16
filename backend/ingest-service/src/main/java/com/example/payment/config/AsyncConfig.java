package com.example.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "outboxDispatchExecutor")
    public TaskExecutor outboxDispatchExecutor(
            @Value("${outbox.dispatcher.core-pool-size:8}") int corePoolSize,
            @Value("${outbox.dispatcher.max-pool-size:16}") int maxPoolSize,
            @Value("${outbox.dispatcher.queue-capacity:2000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-dispatch-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
        executor.setQueueCapacity(queueCapacity);
        executor.setAwaitTerminationSeconds(5);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
