package com.example.settlement.config;

public class ShardContextHolder {

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    public static void setShardKey(String shardKey) {
        contextHolder.set(shardKey);
    }

    public static String getShardKey() {
        return contextHolder.get();
    }

    public static void clear() {
        contextHolder.remove();
    }

    public static void setShardByMerchantId(String merchantId) {
        if (merchantId == null) {
            setShardKey("shard1");
            return;
        }
        try {
            long id = Long.parseLong(merchantId.replaceAll("[^0-9]", ""));
            String shardKey = (id % 2 == 0) ? "shard1" : "shard2";
            setShardKey(shardKey);
        } catch (NumberFormatException e) {
            setShardKey("shard1");
        }
    }
}
