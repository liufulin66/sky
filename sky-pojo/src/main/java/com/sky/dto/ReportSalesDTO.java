package com.sky.dto;

import lombok.Data;

@Data
public class ReportSalesDTO {
    private Integer productType; // 1菜品，2套餐
    private Long productId;
    private String name; // 查询范围内最近的有效成交快照名称
    private Long number;
}
