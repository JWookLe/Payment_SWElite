package com.example.settlement.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String shardKey = ShardContextHolder.getShardKey();
        return shardKey != null ? shardKey : "shard1";
    }
}
