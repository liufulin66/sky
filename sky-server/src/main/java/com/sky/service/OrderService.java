package com.sky.service;

import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;

public interface OrderService {
    OrderSubmitVO submit(OrdersSubmitDTO dto);

    PageResult<OrderVO> historyOrders(Integer page, Integer pageSize, Integer status);

    OrderVO orderDetail(Long id);
}
