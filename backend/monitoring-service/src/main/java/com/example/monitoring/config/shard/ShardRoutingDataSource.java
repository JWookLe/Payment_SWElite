package com.example.monitoring.config.shard;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * ShardContextHolder의 ThreadLocal 값을 기반으로 라우팅하는 DataSource.
 * Spring의 AbstractRoutingDataSource를 확장하여 동적 샤드 라우팅 구현.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String shardKey = ShardContextHolder.getShardKey();
        // ThreadLocal이 설정되지 않은 경우 기본값은 shard1
        return shardKey != null ? shardKey : "shard1";
    }
}
