package com.sky.service.impl;

import com.sky.dto.WorkspaceBusinessDataDTO;
import com.sky.mapper.WorkspaceMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceServiceImplTest {
    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);

    private WorkspaceServiceImpl at(String instant) {
        return new WorkspaceServiceImpl(mapper, Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }

    private WorkspaceBusinessDataDTO summary(String turnover, int valid, int all, int users) {
        WorkspaceBusinessDataDTO dto = new WorkspaceBusinessDataDTO();
        dto.setTurnover(new BigDecimal(turnover));
        dto.setValidOrderCount(valid);
        dto.setAllOrders(all);
        dto.setNewUsers(users);
        return dto;
    }

    @Test
    void emptyOrdersReturnZeroRatiosButKeepIndependentNewUsers() {
        when(mapper.businessData(any(), any())).thenReturn(summary("0", 0, 0, 2));
        var result = at("2026-10-17T04:00:00Z").businessData();
        assertEquals(0.0, result.getTurnover());
        assertEquals(0, result.getValidOrderCount());
        assertEquals(0.0, result.getUnitPrice());
        assertEquals(0.0, result.getOrderCompletionRate());
        assertEquals(2, result.getNewUsers());
    }

    @Test
    void monetaryDivisionAndRateUseExplicitRounding() {
        when(mapper.businessData(any(), any())).thenReturn(summary("30.05", 3, 7, 1));
        var result = at("2026-10-17T04:00:00Z").businessData();
        assertEquals(30.05, result.getTurnover());
        assertEquals(10.02, result.getUnitPrice());
        assertEquals(0.4286, result.getOrderCompletionRate());
        assertEquals(3, result.getValidOrderCount());
        when(mapper.businessData(any(), any())).thenReturn(summary("0.00", 0, 7, 0));
        assertEquals(0.0, at("2026-10-17T04:00:00Z").businessData().getUnitPrice());
    }

    @Test
    void aValidZeroPriceOrderStillCountsAsCompleted() {
        when(mapper.businessData(any(), any())).thenReturn(summary("0.00", 1, 1, 0));
        var result = at("2026-10-17T04:00:00Z").businessData();
        assertEquals(1, result.getValidOrderCount());
        assertEquals(1.0, result.getOrderCompletionRate());
        assertEquals(0.0, result.getUnitPrice());
    }

    @Test
    void midnightUsesShanghaiDateAndEndIsExclusiveNextDay() {
        when(mapper.businessData(any(), any())).thenReturn(summary("0", 0, 0, 0));
        var beforeMidnight = at("2026-10-17T15:59:59Z");
        beforeMidnight.businessData();
        beforeMidnight.overviewOrders();
        var begin = LocalDateTime.of(2026, 10, 17, 0, 0);
        verify(mapper).businessData(begin, begin.plusDays(1));
        verify(mapper).overviewOrders(begin, begin.plusDays(1));
        var afterMidnight = at("2026-10-17T16:00:00Z");
        afterMidnight.businessData();
        afterMidnight.overviewOrders();
        verify(mapper).businessData(begin.plusDays(1), begin.plusDays(2));
        verify(mapper).overviewOrders(begin.plusDays(1), begin.plusDays(2));
    }
}
