package com.example.payment.config.shard;

/**
 * 현재 샤드 키를 저장하는 ThreadLocal 홀더.
 * ShardRoutingDataSource가 어떤 샤드로 라우팅할지 결정할 때 사용.
 */
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

    /**
     * merchant ID 기반 모듈로 라우팅으로 샤드 설정.
     * merchant_id % 2 = 0 → shard1 (VM1)
     * merchant_id % 2 = 1 → shard2 (VM2)
     */
    public static void setShardByMerchantId(String merchantId) {
        try {
            long id = Long.parseLong(merchantId.replaceAll("[^0-9]", ""));
            String shardKey = (id % 2 == 0) ? "shard1" : "shard2";
            setShardKey(shardKey);
        } catch (NumberFormatException e) {
            // merchant ID가 숫자가 아니면 기본값 shard1
            setShardKey("shard1");
        }
    }
}
