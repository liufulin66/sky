package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 日订单聚合，供营业额及订单报表共用，不直接返回前端。 */
@Data
public class ReportDailyOrderDTO {
    private LocalDate date;
    private BigDecimal turnover;
    private Long orderCount;
    private Long validOrderCount;
}
