package com.example.monitoring.config.shard;

/**
 * ThreadLocal을 이용한 샤드 컨텍스트 관리.
 * merchant_id를 기반으로 샤드 키를 자동 설정.
 */
public class ShardContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    /**
     * 현재 스레드의 샤드 키 설정
     */
    public static void setShardKey(String shardKey) {
        CONTEXT.set(shardKey);
    }

    /**
     * 현재 스레드의 샤드 키 조회
     */
    public static String getShardKey() {
        return CONTEXT.get();
    }

    /**
     * 샤드 컨텍스트 초기화
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * merchant_id를 기반으로 샤드 키 자동 설정 (modulo 2 해싱)
     * MERCHANT-123 → 123 (홀수) → shard2
     * MERCHANT-456 → 456 (짝수) → shard1
     */
    public static void setShardByMerchantId(String merchantId) {
        if (merchantId == null || merchantId.isEmpty()) {
            setShardKey("shard1"); // 기본값
            return;
        }

        // "MERCHANT-123" → "123" 추출
        String numericPart = merchantId.replaceAll("[^0-9]", "");
        if (numericPart.isEmpty()) {
            setShardKey("shard1"); // 기본값
            return;
        }

        int merchantNumber = Integer.parseInt(numericPart);
        String shardKey = (merchantNumber % 2 == 0) ? "shard1" : "shard2";
        setShardKey(shardKey);
    }
}
