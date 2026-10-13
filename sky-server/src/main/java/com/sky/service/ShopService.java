package com.sky.service;

public interface ShopService {
    void setStatus(Integer status);

    Integer getStatus();

    /** 提交新订单时调用；已有订单的支付和处理不调用此检查。 */
    void checkOpenForNewOrder();
}
