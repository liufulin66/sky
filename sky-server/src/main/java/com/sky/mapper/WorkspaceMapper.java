package com.sky.mapper;

import com.sky.dto.WorkspaceBusinessDataDTO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface WorkspaceMapper {
    WorkspaceBusinessDataDTO businessData(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    OrderOverViewVO overviewOrders(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    DishOverViewVO overviewDishes();

    SetmealOverViewVO overviewSetmeals();
}
