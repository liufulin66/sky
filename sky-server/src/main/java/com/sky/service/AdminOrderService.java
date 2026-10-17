package com.sky.service;

import com.sky.dto.OrdersPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;

/** 商家操作通过管理端JWT鉴权；与用户订单的归属校验分开。 */
public interface AdminOrderService {
    PageResult<OrderVO> search(OrdersPageQueryDTO query);

    OrderVO detail(Long id);

    OrderStatisticsVO statistics();

    void confirm(Long id);

    void delivery(Long id);

    void complete(Long id);

    void cancel(Long id, String reason);

    void reject(Long id, String reason);
}
