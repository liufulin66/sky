package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.entity.Orders;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface OrderMapper {
    Page<OrderVO> searchForAdmin(OrdersPageQueryDTO query);

    OrderVO getByIdForAdmin(@Param("id") Long id);

    Orders getByIdForUpdate(@Param("id") Long id);

    OrderStatisticsVO statisticsForAdmin();

    int advancePaidStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
                          @Param("toStatus") Integer toStatus, @Param("deliveryTime") LocalDateTime deliveryTime);

    int cancelUnpaidForAdmin(@Param("id") Long id, @Param("reason") String reason,
                             @Param("cancelTime") LocalDateTime cancelTime);

    Orders getByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    void insert(Orders order);

    Page<OrderVO> pageByUserId(@Param("userId") Long userId, @Param("status") Integer status);

    OrderVO getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    Orders getByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    int cancelUnpaid(@Param("id") Long id, @Param("userId") Long userId,
                     @Param("reason") String reason, @Param("cancelTime") LocalDateTime cancelTime);
}
