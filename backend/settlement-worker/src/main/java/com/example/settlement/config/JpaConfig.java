package com.example.settlement.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class JpaConfig {

    @Value("${spring.datasource.shard1.url}")
    private String shard1Url;

    @Value("${spring.datasource.shard1.username}")
    private String shard1Username;

    @Value("${spring.datasource.shard1.password}")
    private String shard1Password;

    @Value("${spring.datasource.shard2.url}")
    private String shard2Url;

    @Value("${spring.datasource.shard2.username}")
    private String shard2Username;

    @Value("${spring.datasource.shard2.password}")
    private String shard2Password;

    @Bean(name = "shard1DataSource")
    public DataSource shard1DataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("HikariPool-Shard1");
        dataSource.setJdbcUrl(shard1Url);
        dataSource.setUsername(shard1Username);
        dataSource.setPassword(shard1Password);
        dataSource.setDriverClassName("org.mariadb.jdbc.Driver");
        dataSource.setMaximumPoolSize(50);
        dataSource.setMinimumIdle(10);
        return dataSource;
    }

    @Bean(name = "shard2DataSource")
    public DataSource shard2DataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("HikariPool-Shard2");
        dataSource.setJdbcUrl(shard2Url);
        dataSource.setUsername(shard2Username);
        dataSource.setPassword(shard2Password);
        dataSource.setDriverClassName("org.mariadb.jdbc.Driver");
        dataSource.setMaximumPoolSize(50);
        dataSource.setMinimumIdle(10);
        return dataSource;
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSource shard1DataSource, DataSource shard2DataSource) {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("shard1", shard1DataSource);
        targetDataSources.put("shard2", shard2DataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(shard1DataSource);
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.example.settlement.domain");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect");
        properties.put("hibernate.jdbc.time_zone", "UTC");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
