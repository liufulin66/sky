package com.sky.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Excel生成所需的结构化数据，与图表接口的CSV展示格式分开。 */
public record ReportExportDataDTO(LocalDate begin, LocalDate end, Summary summary,
                                  List<DailyRow> dailyRows, List<ReportSalesDTO> top10) {
    public record DailyRow(LocalDate date, BigDecimal turnover, long orderCount, long validOrderCount,
                           BigDecimal completionRate, BigDecimal unitPrice, long newUsers, long totalUsers) { }

    public record Summary(BigDecimal turnover, long orderCount, long validOrderCount,
                          BigDecimal completionRate, BigDecimal unitPrice, long newUsers, long totalUsers) { }
}
