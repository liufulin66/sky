package com.sky.service.impl;

import com.sky.dto.ReportDailyOrderDTO;
import com.sky.dto.ReportDailyUserDTO;
import com.sky.dto.ReportSalesDTO;
import com.sky.exception.ReportBusinessException;
import com.sky.mapper.ReportMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportExcelExportTest {
    private final ReportMapper mapper = mock(ReportMapper.class);
    private final LocalDate begin = LocalDate.of(2026, 10, 1);
    private final LocalDate end = begin.plusDays(2);

    private ReportServiceImpl service(String instant) {
        return new ReportServiceImpl(mapper, Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }

    private ReportDailyOrderDTO order(LocalDate date, String amount, long count, long valid) {
        var row = new ReportDailyOrderDTO(); row.setDate(date); row.setTurnover(new BigDecimal(amount));
        row.setOrderCount(count); row.setValidOrderCount(valid); return row;
    }

    private ReportDailyUserDTO user(LocalDate date, long count) {
        var row = new ReportDailyUserDTO(); row.setDate(date); row.setNewUserCount(count); return row;
    }

    private void emptyData(long baseUsers) {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of());
        when(mapper.dailyUsers(any(), any())).thenReturn(List.of(user(null, baseUsers)));
        when(mapper.top10(any(), any())).thenReturn(List.of());
    }

    @Test
    void workbookContainsThreeSheetsAndConsistentTypedDailyAndSummaryData() throws Exception {
        when(mapper.dailyOrders(any(), any())).thenReturn(List.of(order(begin, "10.10", 1, 1), order(end, "20.20", 4, 1)));
        when(mapper.dailyUsers(any(), any())).thenReturn(List.of(user(null, 10), user(begin, 2), user(end, 3)));
        var product = new ReportSalesDTO(); product.setProductType(1); product.setProductId(9007199254740993L);
        product.setName("商品,原名"); product.setNumber(5L);
        when(mapper.top10(any(), any())).thenReturn(List.of(product));
        var file = service("2026-10-17T04:00:00Z").exportExcel(begin, end);
        assertEquals("运营报表_2026-10-01_2026-10-03.xlsx", file.filename());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertEquals(3, book.getNumberOfSheets());
            assertEquals("经营汇总", book.getSheetName(0)); assertEquals("每日明细", book.getSheetName(1));
            assertEquals("销量 Top10", book.getSheetName(2));
            var summary = book.getSheetAt(0);
            assertEquals(30.3, summary.getRow(3).getCell(1).getNumericCellValue(), 0.000001);
            assertEquals(5, summary.getRow(4).getCell(1).getNumericCellValue());
            assertEquals(2, summary.getRow(5).getCell(1).getNumericCellValue());
            assertEquals(0.4, summary.getRow(6).getCell(1).getNumericCellValue());
            assertEquals("40.00%", new DataFormatter(java.util.Locale.US).formatCellValue(summary.getRow(6).getCell(1)));
            assertEquals(15.15, summary.getRow(7).getCell(1).getNumericCellValue());
            assertEquals(5, summary.getRow(8).getCell(1).getNumericCellValue());
            assertEquals(15, summary.getRow(9).getCell(1).getNumericCellValue());
            var daily = book.getSheetAt(1);
            assertEquals(5, daily.getLastRowNum());
            assertEquals(begin, daily.getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(end, daily.getRow(5).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(0, daily.getRow(4).getCell(1).getNumericCellValue());
            assertEquals(12, daily.getRow(4).getCell(7).getNumericCellValue());
            assertEquals(CellType.NUMERIC, daily.getRow(3).getCell(1).getCellType());
            assertEquals("#,##0.00", daily.getRow(3).getCell(1).getCellStyle().getDataFormatString());
            assertNotNull(daily.getPaneInformation());
            var top = book.getSheetAt(2).getRow(3);
            assertEquals(CellType.STRING, top.getCell(2).getCellType());
            assertEquals("9007199254740993", top.getCell(2).getStringCellValue());
            assertEquals("商品,原名", top.getCell(3).getStringCellValue());
            assertEquals(5, top.getCell(4).getNumericCellValue());
        }
        verify(mapper).dailyOrders(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        verify(mapper).dailyUsers(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        verify(mapper).top10(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    @Test
    void defaultPeriodUsesThirtyCompleteShanghaiDaysAndChangesAtMidnight() throws Exception {
        emptyData(0);
        var before = service("2026-10-17T15:59:59Z").exportExcel(null, null);
        assertEquals("运营报表_2026-09-17_2026-10-16.xlsx", before.filename());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(before.content()))) {
            assertEquals(32, book.getSheetAt(1).getLastRowNum());
            assertEquals(LocalDate.of(2026, 9, 17), book.getSheetAt(1).getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(LocalDate.of(2026, 10, 16), book.getSheetAt(1).getRow(32).getCell(0).getLocalDateTimeCellValue().toLocalDate());
        }
        var after = service("2026-10-17T16:00:00Z").exportExcel(null, null);
        assertEquals("运营报表_2026-09-18_2026-10-17.xlsx", after.filename());
        verify(mapper).dailyOrders(LocalDate.of(2026, 9, 17).atStartOfDay(), LocalDate.of(2026, 10, 17).atStartOfDay());
        verify(mapper).dailyOrders(LocalDate.of(2026, 9, 18).atStartOfDay(), LocalDate.of(2026, 10, 18).atStartOfDay());
    }

    @Test
    void noOrdersStillExportsDailyZerosHistoricalUsersAndEmptyTopMessage() throws Exception {
        emptyData(7);
        var file = service("2026-10-17T04:00:00Z").exportExcel(begin, begin);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            var summary = book.getSheetAt(0);
            assertEquals(0, summary.getRow(3).getCell(1).getNumericCellValue());
            assertEquals(0, summary.getRow(6).getCell(1).getNumericCellValue());
            assertEquals(0, summary.getRow(7).getCell(1).getNumericCellValue());
            assertEquals(7, summary.getRow(9).getCell(1).getNumericCellValue());
            assertEquals(7, book.getSheetAt(1).getRow(3).getCell(7).getNumericCellValue());
            assertTrue(book.getSheetAt(2).getRow(3).getCell(0).getStringCellValue().contains("暂无"));
        }
    }

    @Test
    void rejectsPartialReversedAndOverlongDatesBeforeQueryingAndSupportsLeapYear() throws Exception {
        var service = service("2026-10-17T04:00:00Z");
        assertThrows(ReportBusinessException.class, () -> service.exportExcel(begin, null));
        assertThrows(ReportBusinessException.class, () -> service.exportExcel(null, end));
        assertThrows(ReportBusinessException.class, () -> service.exportExcel(end, begin));
        assertThrows(ReportBusinessException.class, () -> service.exportExcel(begin, begin.plusDays(366)));
        verifyNoInteractions(mapper);
        emptyData(0);
        var file = service.exportExcel(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertEquals(368, book.getSheetAt(1).getLastRowNum());
        }
    }

    @Test
    void productNamesAreWrittenAsTextAndNeverAsExcelFormulas() throws Exception {
        emptyData(0);
        var product = new ReportSalesDTO(); product.setProductType(2); product.setProductId(1L);
        product.setName("=1+1"); product.setNumber(1L);
        when(mapper.top10(any(), any())).thenReturn(List.of(product));
        var file = service("2026-10-17T04:00:00Z").exportExcel(begin, begin);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            var cell = book.getSheetAt(2).getRow(3).getCell(3);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("=1+1", cell.getStringCellValue());
        }
    }
}
