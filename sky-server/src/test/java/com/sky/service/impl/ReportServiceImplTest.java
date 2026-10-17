package com.sky.service.impl;

import com.sky.dto.ReportDailyOrderDTO;
import com.sky.dto.ReportDailyUserDTO;
import com.sky.dto.ReportSalesDTO;
import com.sky.exception.ReportBusinessException;
import com.sky.mapper.ReportMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportServiceImplTest {
    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportServiceImpl service = new ReportServiceImpl(mapper);
    private final LocalDate begin = LocalDate.of(2026, 10, 1);
    private final LocalDate end = begin.plusDays(2);

    private ReportDailyOrderDTO order(LocalDate date, String amount, long count, long valid) {
        ReportDailyOrderDTO row = new ReportDailyOrderDTO();
        row.setDate(date); row.setTurnover(new BigDecimal(amount));
        row.setOrderCount(count); row.setValidOrderCount(valid);
        return row;
    }

    private ReportDailyUserDTO user(LocalDate date, long count) {
        ReportDailyUserDTO row = new ReportDailyUserDTO(); row.setDate(date); row.setNewUserCount(count);
        return row;
    }

    @Test
    void turnoverSortsDatesFillsMissingDaysAndUsesOneGroupedQuery() {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of(
                order(end, "20.20", 1, 1), order(begin, "10.10", 1, 1)));
        var result = service.turnoverStatistics(begin, end);
        assertEquals("2026-10-01,2026-10-02,2026-10-03", result.getDateList());
        assertEquals("10.10,0.00,20.20", result.getTurnoverList());
        verify(mapper).dailyOrders(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    @Test
    void usersStartWithHistoricalBaseAndCarryTotalsAcrossEmptyDays() {
        when(mapper.dailyUsers(any(), any())).thenReturn(List.of(user(end, 3), user(null, 10), user(begin, 2)));
        var result = service.userStatistics(begin, end);
        assertEquals("2,0,3", result.getNewUserList());
        assertEquals("12,12,15", result.getTotalUserList());
        verify(mapper, times(1)).dailyUsers(any(), any());
    }

    @Test
    void ordersUseWholePeriodRatioInsteadOfAverageOfDailyRates() {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of(
                order(begin, "10", 1, 1), order(end, "20", 9, 1)));
        var result = service.ordersStatistics(begin, end);
        assertEquals("1,0,9", result.getOrderCountList());
        assertEquals("1,0,1", result.getValidOrderCountList());
        assertEquals(10, result.getTotalOrderCount());
        assertEquals(2, result.getValidOrderCount());
        assertEquals(0.2, result.getOrderCompletionRate());
    }

    @Test
    void emptyDataReturnsAlignedZerosAndEmptyRanking() {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of());
        when(mapper.dailyUsers(any(), any())).thenReturn(List.of(user(null, 0)));
        when(mapper.top10(any(), any())).thenReturn(List.of());
        assertEquals("0.00,0.00,0.00", service.turnoverStatistics(begin, end).getTurnoverList());
        assertEquals("0,0,0", service.userStatistics(begin, end).getTotalUserList());
        var orders = service.ordersStatistics(begin, end);
        assertEquals(0, orders.getTotalOrderCount());
        assertEquals(0.0, orders.getOrderCompletionRate());
        var top = service.top10(begin, end);
        assertEquals("", top.getNameList()); assertEquals("", top.getNumberList());
    }

    @Test
    void top10EscapesOnlyDisplayCommaAndPreservesSourceSnapshot() {
        ReportSalesDTO row = new ReportSalesDTO(); row.setProductType(1); row.setProductId(10L);
        row.setName("菜品,套餐"); row.setNumber(3L);
        ReportSalesDTO unnamed = new ReportSalesDTO(); unnamed.setProductType(2); unnamed.setProductId(20L);
        unnamed.setName(" "); unnamed.setNumber(1L);
        when(mapper.top10(any(), any())).thenReturn(List.of(row, unnamed));
        var result = service.top10(begin, end);
        assertEquals("菜品，套餐,套餐#20", result.getNameList());
        assertEquals("3,1", result.getNumberList());
        assertEquals("菜品,套餐", row.getName());
    }

    @Test
    void dateValidationRunsBeforeEveryQueryAndAccepts366InclusiveDays() {
        for (LocalDate[] range : List.of(new LocalDate[]{null, end}, new LocalDate[]{begin, null},
                new LocalDate[]{end, begin}, new LocalDate[]{begin, begin.plusDays(366)},
                new LocalDate[]{LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31)})) {
            assertThrows(ReportBusinessException.class, () -> service.turnoverStatistics(range[0], range[1]));
            assertThrows(ReportBusinessException.class, () -> service.userStatistics(range[0], range[1]));
            assertThrows(ReportBusinessException.class, () -> service.ordersStatistics(range[0], range[1]));
            assertThrows(ReportBusinessException.class, () -> service.top10(range[0], range[1]));
        }
        verifyNoInteractions(mapper);
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of());
        var leapYear = service.turnoverStatistics(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        assertEquals(366, leapYear.getDateList().split(",").length);
        assertEquals("2026-10-01", service.turnoverStatistics(begin, begin).getDateList());
    }

    @Test
    void completionRateRoundsAndIntegerTotalsCannotWrapAround() {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of(order(begin, "1.00", 3, 1)));
        assertEquals(0.3333, service.ordersStatistics(begin, begin).getOrderCompletionRate());
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of(order(begin, "1.00", 2147483648L, 1)));
        assertThrows(ReportBusinessException.class, () -> service.ordersStatistics(begin, begin));
    }
}
