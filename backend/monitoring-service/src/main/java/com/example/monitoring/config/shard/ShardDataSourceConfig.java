package com.example.monitoring.config.shard;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * DB 샤딩을 위한 멀티 데이터소스 설정.
 * shard1: VM1 MariaDB (172.25.0.37:13306)
 * shard2: VM2 MariaDB (172.25.0.79:13307)
 */
@Configuration
public class ShardDataSourceConfig {

    /**
     * Shard1 데이터소스 프로퍼티
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard1")
    public DataSourceProperties shard1DataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Shard2 데이터소스 프로퍼티
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard2")
    public DataSourceProperties shard2DataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Shard1 HikariCP 데이터소스
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard1.hikari")
    public HikariDataSource shard1DataSource(
            @Qualifier("shard1DataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Shard2 HikariCP 데이터소스
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard2.hikari")
    public HikariDataSource shard2DataSource(
            @Qualifier("shard2DataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * 라우팅 데이터소스 (Primary)
     * ShardContextHolder의 샤드 키에 따라 shard1 또는 shard2로 라우팅
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("shard1DataSource") HikariDataSource shard1,
            @Qualifier("shard2DataSource") HikariDataSource shard2) throws SQLException {

        // 두 데이터소스 모두 초기화 (Lazy 초기화 방지)
        shard1.getConnection().close();
        shard2.getConnection().close();

        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard1", shard1);
        targetDataSources.put("shard2", shard2);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(shard1);
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }
}
