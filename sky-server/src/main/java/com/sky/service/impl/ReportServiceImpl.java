package com.sky.service.impl;

import com.sky.dto.ReportDailyOrderDTO;
import com.sky.dto.ReportDailyUserDTO;
import com.sky.dto.ReportSalesDTO;
import com.sky.dto.ReportExportDataDTO;
import com.sky.dto.ReportExportFileDTO;
import com.sky.exception.ReportBusinessException;
import com.sky.mapper.ReportMapper;
import com.sky.service.ReportService;
import com.sky.utils.ReportExcelWriter;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {
    private final ReportMapper reportMapper;
    private final Clock clock;

    @Autowired
    public ReportServiceImpl(ReportMapper reportMapper) {
        this(reportMapper, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    ReportServiceImpl(ReportMapper reportMapper, Clock clock) {
        this.reportMapper = reportMapper;
        this.clock = clock;
    }

    @Override
    public TurnoverReportVO turnoverStatistics(LocalDate begin, LocalDate end) {
        DateRange range = dateRange(begin, end);
        Map<LocalDate, ReportDailyOrderDTO> rows = dailyOrders(range);
        StringJoiner values = new StringJoiner(",");
        for (LocalDate date : range.dates()) {
            ReportDailyOrderDTO row = rows.get(date);
            BigDecimal amount = row == null ? BigDecimal.ZERO : row.getTurnover();
            values.add(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return TurnoverReportVO.builder().dateList(dateList(range)).turnoverList(values.toString()).build();
    }

    @Override
    public UserReportVO userStatistics(LocalDate begin, LocalDate end) {
        DateRange range = dateRange(begin, end);
        List<ReportDailyUserDTO> rows = reportMapper.dailyUsers(range.start(), range.endExclusive());
        Map<LocalDate, Long> daily = new HashMap<>();
        long total = 0;
        for (ReportDailyUserDTO row : rows) {
            if (row.getDate() == null) { total = row.getNewUserCount(); }
            else { daily.put(row.getDate(), row.getNewUserCount()); }
        }
        StringJoiner newUsers = new StringJoiner(",");
        StringJoiner totalUsers = new StringJoiner(",");
        for (LocalDate date : range.dates()) {
            long added = daily.getOrDefault(date, 0L);
            total += added;
            newUsers.add(Long.toString(added));
            totalUsers.add(Long.toString(total));
        }
        return UserReportVO.builder().dateList(dateList(range)).newUserList(newUsers.toString())
                .totalUserList(totalUsers.toString()).build();
    }

    @Override
    public OrderReportVO ordersStatistics(LocalDate begin, LocalDate end) {
        DateRange range = dateRange(begin, end);
        Map<LocalDate, ReportDailyOrderDTO> rows = dailyOrders(range);
        StringJoiner counts = new StringJoiner(",");
        StringJoiner validCounts = new StringJoiner(",");
        long all = 0;
        long valid = 0;
        for (LocalDate date : range.dates()) {
            ReportDailyOrderDTO row = rows.get(date);
            long count = row == null ? 0 : row.getOrderCount();
            long validCount = row == null ? 0 : row.getValidOrderCount();
            counts.add(Long.toString(count));
            validCounts.add(Long.toString(validCount));
            all += count;
            valid += validCount;
        }
        double rate = all == 0 ? 0 : BigDecimal.valueOf(valid)
                .divide(BigDecimal.valueOf(all), 4, RoundingMode.HALF_UP).doubleValue();
        return OrderReportVO.builder().dateList(dateList(range)).orderCountList(counts.toString())
                .validOrderCountList(validCounts.toString()).totalOrderCount(integerCount(all))
                .validOrderCount(integerCount(valid)).orderCompletionRate(rate).build();
    }

    @Override
    public SalesTop10ReportVO top10(LocalDate begin, LocalDate end) {
        DateRange range = dateRange(begin, end);
        List<ReportSalesDTO> rows = reportMapper.top10(range.start(), range.endExclusive());
        StringJoiner names = new StringJoiner(",");
        StringJoiner numbers = new StringJoiner(",");
        for (ReportSalesDTO row : rows) {
            String name = row.getName();
            if (name == null || name.isBlank()) {
                name = (Integer.valueOf(1).equals(row.getProductType()) ? "菜品#" : "套餐#") + row.getProductId();
            }
            names.add(name.replace(',', '，')); // 仅适配报表CSV展示，不修改数据库快照。
            numbers.add(Long.toString(row.getNumber()));
        }
        return SalesTop10ReportVO.builder().nameList(names.toString()).numberList(numbers.toString()).build();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExportFileDTO exportExcel(LocalDate begin, LocalDate end) {
        if (begin == null && end == null) {
            LocalDate today = LocalDate.now(clock);
            end = today.minusDays(1);
            begin = end.minusDays(29);
        }
        DateRange range = dateRange(begin, end); // 只传一个日期时沿用必填校验。
        Map<LocalDate, ReportDailyOrderDTO> orders = dailyOrders(range);
        Map<LocalDate, Long> users = new HashMap<>();
        long totalUsers = 0;
        for (ReportDailyUserDTO row : reportMapper.dailyUsers(range.start(), range.endExclusive())) {
            if (row.getDate() == null) { totalUsers = row.getNewUserCount(); }
            else { users.put(row.getDate(), row.getNewUserCount()); }
        }
        List<ReportSalesDTO> top = reportMapper.top10(range.start(), range.endExclusive());
        List<ReportExportDataDTO.DailyRow> rows = new ArrayList<>();
        BigDecimal turnover = BigDecimal.ZERO;
        long all = 0, valid = 0, addedUsers = 0;
        for (LocalDate date : range.dates()) {
            ReportDailyOrderDTO order = orders.get(date);
            BigDecimal amount = order == null ? BigDecimal.ZERO : order.getTurnover();
            long count = order == null ? 0 : order.getOrderCount();
            long validCount = order == null ? 0 : order.getValidOrderCount();
            long added = users.getOrDefault(date, 0L);
            totalUsers += added;
            rows.add(new ReportExportDataDTO.DailyRow(date, amount, count, validCount,
                    ratio(validCount, count), average(amount, validCount), added, totalUsers));
            turnover = turnover.add(amount);
            all += count;
            valid += validCount;
            addedUsers += added;
        }
        var summary = new ReportExportDataDTO.Summary(turnover, all, valid, ratio(valid, all),
                average(turnover, valid), addedUsers, totalUsers);
        try {
            byte[] content = ReportExcelWriter.write(new ReportExportDataDTO(begin, end, summary, rows, top));
            return new ReportExportFileDTO("运营报表_" + begin + "_" + end + ".xlsx", content);
        } catch (IOException ex) {
            log.error("生成运营报表失败", ex);
            throw error("报表文件生成失败，请稍后重试");
        }
    }

    private BigDecimal ratio(long valid, long count) {
        return count == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(valid).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal average(BigDecimal amount, long count) {
        return count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private Map<LocalDate, ReportDailyOrderDTO> dailyOrders(DateRange range) {
        return reportMapper.dailyOrders(range.start(), range.endExclusive()).stream()
                .collect(Collectors.toMap(ReportDailyOrderDTO::getDate, Function.identity()));
    }

    private String dateList(DateRange range) {
        return range.dates().stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }

    private DateRange dateRange(LocalDate begin, LocalDate end) {
        if (begin == null || end == null) { throw error("begin和end不能为空，格式为yyyy-MM-dd"); }
        if (begin.isAfter(end)) { throw error("开始日期不能晚于结束日期"); }
        if (ChronoUnit.DAYS.between(begin, end) >= 366) { throw error("日期范围最多包含366天"); }
        if (begin.isBefore(LocalDate.of(1000, 1, 1)) || !end.isBefore(LocalDate.of(9999, 12, 31))) {
            throw error("日期范围超出数据库支持范围");
        }
        List<LocalDate> dates = begin.datesUntil(end.plusDays(1)).toList();
        return new DateRange(dates, begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    private int integerCount(long count) {
        if (count > Integer.MAX_VALUE) { throw error("订单数量超出当前接口支持范围"); }
        return (int) count;
    }

    private ReportBusinessException error(String message) { return new ReportBusinessException(message); }

    private record DateRange(List<LocalDate> dates, LocalDateTime start, LocalDateTime endExclusive) { }
}
