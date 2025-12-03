package com.example.payment.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import javax.sql.DataSource;

/**
 * ShedLock Configuration for distributed task scheduling.
 *
 * Ensures that scheduled tasks (like OutboxPollingScheduler) run on only one instance
 * in a distributed system, preventing duplicate processing and deadlock contention.
 *
 * Key Features:
 * - Distributed locking using shedlock table in MariaDB
 * - Prevents multiple VMs from executing the same scheduled task simultaneously
 * - Automatic lock cleanup after task completion or timeout
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class ShedLockConfiguration {

    /**
     * Configure ShedLock provider for JDBC-based locking.
     */
    @Bean
    public LockProvider lockProvider(@Qualifier("shard1DataSource") DataSource shard1DataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                // 락은 shard1 DB에만 저장해 다중 샤드 환경에서 안정적으로 사용
                .withJdbcTemplate(new JdbcTemplate(shard1DataSource))
                .usingDbTime()
                .build()
        );
    }
}
