package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface OrderRepetitionMapper {
    Long getOrderIdByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    void insert(@Param("userId") Long userId, @Param("requestId") String requestId,
                @Param("orderId") Long orderId, @Param("createTime") LocalDateTime createTime);
}
