package com.sky.mapper;

import com.sky.dto.ReportDailyOrderDTO;
import com.sky.dto.ReportDailyUserDTO;
import com.sky.dto.ReportSalesDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReportMapper {
    List<ReportDailyOrderDTO> dailyOrders(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    List<ReportDailyUserDTO> dailyUsers(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    List<ReportSalesDTO> top10(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);
}
