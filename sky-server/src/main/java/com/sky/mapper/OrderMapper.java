package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.entity.Orders;
import com.sky.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
    Orders getByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    void insert(Orders order);

    Page<OrderVO> pageByUserId(@Param("userId") Long userId, @Param("status") Integer status);

    OrderVO getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
