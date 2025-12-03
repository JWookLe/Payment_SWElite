package com.example.payment.config.shard;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * ShardContextHolder 기반으로 샤드를 선택하는 라우팅 DataSource.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String shardKey = ShardContextHolder.getShardKey();
        // 샤드 키가 설정되지 않으면 기본값 shard1
        return shardKey != null ? shardKey : "shard1";
    }
}
