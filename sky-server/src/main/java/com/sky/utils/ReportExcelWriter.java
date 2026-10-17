package com.sky.utils;

import com.sky.dto.ReportExportDataDTO;
import com.sky.dto.ReportSalesDTO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;

/** 只负责Excel布局和单元格类型，不查询数据库或重新定义统计口径。 */
public final class ReportExcelWriter {
    private ReportExcelWriter() { }

    public static byte[] write(ReportExportDataDTO data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            summary(workbook, data, styles);
            daily(workbook, data, styles);
            top10(workbook, data, styles);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void summary(XSSFWorkbook workbook, ReportExportDataDTO data, Styles styles) {
        Sheet sheet = sheet(workbook, "经营汇总", data, styles, "指标", "数值");
        var summary = data.summary();
        String[] labels = {"营业额（元）", "订单总数", "有效订单数", "订单完成率", "平均客单价（元）", "新增用户数", "截至期末累计用户数"};
        for (int i = 0; i < labels.length; i++) { text(sheet.createRow(i + 3), 0, labels[i], styles.body); }
        money(sheet.getRow(3), 1, summary.turnover(), styles.money);
        number(sheet.getRow(4), 1, summary.orderCount(), styles.integer);
        number(sheet.getRow(5), 1, summary.validOrderCount(), styles.integer);
        money(sheet.getRow(6), 1, summary.completionRate(), styles.percent);
        money(sheet.getRow(7), 1, summary.unitPrice(), styles.money);
        number(sheet.getRow(8), 1, summary.newUsers(), styles.integer);
        number(sheet.getRow(9), 1, summary.totalUsers(), styles.integer);
        Row note = sheet.createRow(11);
        note.setHeightInPoints(54);
        text(note, 0, "统计口径：按下单日期归属，已完成且已支付的订单计入有效订单和营业额。完成率为有效订单总数÷全部订单总数。", styles.note);
        sheet.addMergedRegion(new CellRangeAddress(11, 11, 0, 1));
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 58 * 256);
        workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, 1, 0, 11);
    }

    private static void daily(XSSFWorkbook workbook, ReportExportDataDTO data, Styles styles) {
        Sheet sheet = sheet(workbook, "每日明细", data, styles, "日期", "营业额（元）", "订单总数", "有效订单数",
                "订单完成率", "平均客单价（元）", "新增用户数", "累计用户数");
        int index = 3;
        for (var item : data.dailyRows()) {
            Row row = sheet.createRow(index++);
            Cell date = row.createCell(0);
            date.setCellValue(item.date().atStartOfDay());
            date.setCellStyle(styles.date);
            money(row, 1, item.turnover(), styles.money);
            number(row, 2, item.orderCount(), styles.integer);
            number(row, 3, item.validOrderCount(), styles.integer);
            money(row, 4, item.completionRate(), styles.percent);
            money(row, 5, item.unitPrice(), styles.money);
            number(row, 6, item.newUsers(), styles.integer);
            number(row, 7, item.totalUsers(), styles.integer);
        }
        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(5, 22 * 256);
        sheet.setAutoFilter(new CellRangeAddress(2, index - 1, 0, 7));
        workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, 7, 0, index - 1);
    }

    private static void top10(XSSFWorkbook workbook, ReportExportDataDTO data, Styles styles) {
        Sheet sheet = sheet(workbook, "销量 Top10", data, styles, "排名", "商品类型", "商品ID", "商品名称", "销量（份）");
        int index = 3;
        for (ReportSalesDTO item : data.top10()) {
            Row row = sheet.createRow(index);
            number(row, 0, index - 2, styles.integer);
            boolean dish = Integer.valueOf(1).equals(item.getProductType());
            text(row, 1, dish ? "菜品" : "套餐", styles.body);
            text(row, 2, item.getProductId().toString(), styles.body); // 防止大整数ID在Excel中丢失精度。
            String name = item.getName();
            if (name == null || name.isBlank()) { name = (dish ? "菜品#" : "套餐#") + item.getProductId(); }
            text(row, 3, name, styles.body); // 单元格独立存储，保留英文逗号及完整成交名称。
            number(row, 4, item.getNumber(), styles.integer);
            index++;
        }
        if (data.top10().isEmpty()) {
            text(sheet.createRow(index++), 0, "所选日期范围内暂无有效商品销量", styles.note);
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 4));
        } else { sheet.setAutoFilter(new CellRangeAddress(2, index - 1, 0, 4)); }
        sheet.setColumnWidth(0, 9 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 24 * 256);
        sheet.setColumnWidth(3, 40 * 256);
        workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, 4, 0, index - 1);
    }

    private static Sheet sheet(XSSFWorkbook workbook, String name, ReportExportDataDTO data, Styles styles, String... headers) {
        Sheet sheet = workbook.createSheet(name);
        sheet.setDefaultColumnWidth(17);
        sheet.setDefaultRowHeightInPoints(24);
        sheet.setDisplayGridlines(false);
        Row title = sheet.createRow(0);
        title.setHeightInPoints(34);
        text(title, 0, "苍穹外卖 · " + name, styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));
        Row period = sheet.createRow(1);
        text(period, 0, "统计日期：" + data.begin() + " 至 " + data.end() + "（北京时间，包含起止日期）", styles.note);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, headers.length - 1));
        Row header = sheet.createRow(2);
        header.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) { text(header, i, headers[i], styles.header); }
        sheet.createFreezePane(0, 3);
        sheet.setRepeatingRows(new CellRangeAddress(0, 2, -1, -1));
        sheet.setFitToPage(true);
        PrintSetup print = sheet.getPrintSetup();
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        print.setLandscape(true);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        return sheet;
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 每个工作簿只创建一次样式，避免按行创建导致样式数量膨胀。 */
    private static final class Styles {
        private final CellStyle title, header, body, money, integer, percent, date, note;

        private Styles(XSSFWorkbook workbook) {
            Font normal = workbook.createFont(); normal.setFontName("Microsoft YaHei"); normal.setFontHeightInPoints((short) 11);
            Font heading = workbook.createFont(); heading.setFontName("Microsoft YaHei");
            heading.setFontHeightInPoints((short) 18); heading.setBold(true); heading.setColor(IndexedColors.DARK_BLUE.getIndex());
            Font white = workbook.createFont(); white.setFontName("Microsoft YaHei");
            white.setFontHeightInPoints((short) 11); white.setBold(true); white.setColor(IndexedColors.WHITE.getIndex());
            body = workbook.createCellStyle(); body.setFont(normal); body.setVerticalAlignment(VerticalAlignment.CENTER);
            body.setBorderBottom(BorderStyle.HAIR); body.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            title = workbook.createCellStyle(); title.setFont(heading); title.setVerticalAlignment(VerticalAlignment.CENTER);
            header = workbook.createCellStyle(); header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER); header.setVerticalAlignment(VerticalAlignment.CENTER);
            money = format(workbook, body, "#,##0.00"); integer = format(workbook, body, "#,##0");
            percent = format(workbook, body, "0.00%"); date = format(workbook, body, "yyyy-mm-dd");
            note = workbook.createCellStyle(); note.setFont(normal); note.setWrapText(true); note.setVerticalAlignment(VerticalAlignment.CENTER);
        }

        private CellStyle format(XSSFWorkbook workbook, CellStyle base, String pattern) {
            CellStyle style = workbook.createCellStyle(); style.cloneStyleFrom(base);
            style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
            return style;
        }
    }
}
