package com.sky.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ReportDailyUserDTO {
    // date为null的唯一一行表示查询开始日之前的用户基数。
    private LocalDate date;
    private Long newUserCount;
}
