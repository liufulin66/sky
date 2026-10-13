package com.sky.constant;

/** 店铺营业状态及其 Redis 存储约定。 */
public final class ShopStatusConstant {
    public static final Integer CLOSED = 0;
    public static final Integer OPEN = 1;
    public static final String REDIS_KEY = "sky:shop:status";

    private ShopStatusConstant() {
    }
}
