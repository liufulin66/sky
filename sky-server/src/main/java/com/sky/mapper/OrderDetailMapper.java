package com.sky.mapper;

import com.sky.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderDetailMapper {
    void insertBatch(@Param("details") List<OrderDetail> details);

    List<OrderDetail> listByOrderIdsForAdmin(@Param("orderIds") List<Long> orderIds);

    List<OrderDetail> listByOrderIdsAndUserId(@Param("orderIds") List<Long> orderIds,
                                           @Param("userId") Long userId);
}
