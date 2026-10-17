package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 数据库聚合结果，仅在Mapper与Service之间传递，金额保持十进制精度。 */
@Data
public class WorkspaceBusinessDataDTO {
    private BigDecimal turnover;
    private Integer validOrderCount;
    private Integer allOrders;
    private Integer newUsers;
}
