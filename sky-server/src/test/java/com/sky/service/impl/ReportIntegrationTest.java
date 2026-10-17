package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.controller.admin.ReportController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.mapper.ReportMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.ReportService;
import com.sky.utils.JwtUtil;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 生产SQL、Service及HTTP鉴权；仅创建、清理随机前缀测试表，不操作业务表。 */
@EnabledIfSystemProperty(named = "report.mysql.tests", matches = "true")
class ReportIntegrationTest {
    private final String tablePrefix = "rpt_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)(\\b(?:from|join|into|update|table)\\s+)(`?)(order_detail|orders|user|dish|setmeal)\\b\\2");
    private final List<String> createdTables = new ArrayList<>();
    private Connection connection;
    private GenericWebApplicationContext context;
    private MockMvc mvc;
    private DriverManagerDataSource dataSource;
    private Runnable afterOrderQuery;

    @BeforeEach
    void setUp() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-dev.yml"));
        Properties props = yaml.getObject();
        String p = "sky.datasource.";
        String url = "jdbc:mysql://" + props.getProperty(p + "host") + ":" + props.getProperty(p + "port")
                + "/" + props.getProperty(p + "database")
                + "?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000";
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                props.getProperty(p + "username"), props.getProperty(p + "password"));
        dataSource = source;
        connection = source.getConnection();
        String baseline = Files.readString(Path.of("../sky-take-out.sql"));
        for (String table : List.of("orders", "order_detail", "user")) {
            var match = Pattern.compile("(?s)CREATE TABLE `" + table + "`.*?;").matcher(baseline);
            assertTrue(match.find(), "Missing baseline table " + table);
            execute(match.group()); createdTables.add(tablePrefix + table);
        }
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(
                new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new AfterOrderQueryInterceptor());
        String resource = "mapper/ReportMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            String xml = qualify(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        ReportMapper mapper = template.getMapper(ReportMapper.class);
        context = new GenericWebApplicationContext(); context.setServletContext(new MockServletContext());
        new AnnotatedBeanDefinitionReader(context).register(TestConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ReportService.class, () -> new ReportServiceImpl(mapper,
                Clock.fixed(Instant.parse("2026-10-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))));
        context.registerBean(JwtProperties.class, () -> {
            JwtProperties jwt = new JwtProperties();
            jwt.setAdminTokenName("token"); jwt.setAdminSecretKey("report-integration-admin-secret-32-bytes");
            jwt.setUserTokenName("authentication"); jwt.setUserSecretKey("report-integration-user-secret-32-bytes");
            return jwt;
        });
        context.refresh(); mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId(); if (context != null) { context.close(); }
        if (connection != null) {
            try {
                for (String table : createdTables) {
                    if (!table.startsWith(tablePrefix) || !table.matches("[a-z0-9_]+")) {
                        throw new IllegalStateException("Unexpected test table name");
                    }
                    try (Statement statement = connection.createStatement()) { statement.execute("DROP TABLE `" + table + "`"); }
                }
            } finally { connection.close(); }
        }
    }

    private String qualify(String sql) {
        return TABLE_REFERENCE.matcher(sql).replaceAll(m -> m.group(1) + "`" + tablePrefix + m.group(3) + "`");
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(qualify(sql)); }
    }

    private String token() {
        return JwtUtil.createJWT(context.getBean(JwtProperties.class).getAdminSecretKey(), 60000,
                Map.of(JwtClaimsConstant.EMP_ID, 1L));
    }

    private JSONObject data(String route) throws Exception {
        return JSON.parseObject(mvc.perform(get("/admin/report/" + route).header("token", token())
                        .param("begin", "2026-10-01").param("end", "2026-10-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");
    }

    private void order(int id, int status, int paid, String amount, String time) throws Exception {
        try (var statement = connection.prepareStatement(qualify(
                "INSERT INTO orders (id,number,status,pay_status,user_id,address_book_id,order_time,amount) VALUES (?,?,?,?,5,1,?,?)"))) {
            statement.setInt(1, id); statement.setString(2, "report-" + id);
            statement.setInt(3, status); statement.setInt(4, paid);
            statement.setString(5, time); statement.setBigDecimal(6, new BigDecimal(amount)); statement.executeUpdate();
        }
    }

    private void item(int id, int order, Long dish, Long setmeal, String name, int quantity) throws Exception {
        try (var statement = connection.prepareStatement(qualify(
                "INSERT INTO order_detail (id,order_id,dish_id,setmeal_id,name,number,amount,dish_flavor) VALUES (?,?,?,?,?,?,1.00,?)"))) {
            statement.setInt(1, id); statement.setInt(2, order); statement.setObject(3, dish); statement.setObject(4, setmeal);
            statement.setString(5, name); statement.setInt(6, quantity); statement.setString(7, "flavor-" + id);
            statement.executeUpdate();
        }
    }

    @Test
    void emptyReportsKeepEveryDateAndZeroTotals() throws Exception {
        JSONObject turnover = data("turnoverStatistics");
        assertEquals("2026-10-01,2026-10-02,2026-10-03", turnover.getString("dateList"));
        assertEquals("0.00,0.00,0.00", turnover.getString("turnoverList"));
        JSONObject users = data("userStatistics");
        assertEquals("0,0,0", users.getString("newUserList"));
        assertEquals("0,0,0", users.getString("totalUserList"));
        JSONObject orders = data("ordersStatistics");
        assertEquals("0,0,0", orders.getString("orderCountList"));
        assertEquals(0, orders.getIntValue("totalOrderCount"));
        assertEquals(0.0, orders.getDoubleValue("orderCompletionRate"));
        JSONObject top = data("top10");
        assertEquals("", top.getString("nameList")); assertEquals("", top.getString("numberList"));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void dailyOrdersRespectPaymentStatusDateEdgesAndMissingDays() throws Exception {
        order(1, 5, 1, "10.10", "2026-10-01 00:00:00");
        order(2, 5, 1, "20.20", "2026-10-03 23:59:59");
        order(3, 5, 0, "999.00", "2026-10-03 12:00:00");
        order(4, 5, 2, "888.00", "2026-10-03 12:00:00");
        order(5, 6, 0, "777.00", "2026-10-03 12:00:00");
        order(6, 4, 1, "666.00", "2026-10-03 12:00:00");
        order(7, 1, 0, "555.00", "2026-10-03 12:00:00");
        order(8, 5, 1, "444.00", "2026-09-30 23:59:59");
        order(9, 5, 1, "333.00", "2026-10-04 00:00:00");
        order(10, 5, 1, "0.00", "2026-10-03 12:00:00");
        execute("UPDATE orders SET delivery_time='2026-10-01 12:00:00' WHERE id=8");
        assertEquals("10.10,0.00,20.20", data("turnoverStatistics").getString("turnoverList"));
        JSONObject result = data("ordersStatistics");
        assertEquals("1,0,7", result.getString("orderCountList"));
        assertEquals("1,0,2", result.getString("validOrderCountList"));
        assertEquals(8, result.getIntValue("totalOrderCount"));
        assertEquals(3, result.getIntValue("validOrderCount"));
        assertEquals(0.375, result.getDoubleValue("orderCompletionRate"));
    }

    @Test
    void userTotalsIncludeHistoricalBaseAndExcludeNextDayMidnight() throws Exception {
        execute("INSERT INTO user (id,openid,create_time) VALUES "
                + "(1,'old','2020-01-01 00:00:00'),(2,'previous','2026-09-30 23:59:59'),"
                + "(3,'first','2026-10-01 00:00:00'),(4,'last','2026-10-03 23:59:59'),"
                + "(5,'excluded','2026-10-04 00:00:00')");
        JSONObject users = data("userStatistics");
        assertEquals("1,0,1", users.getString("newUserList"));
        assertEquals("3,3,4", users.getString("totalUserList"));
    }

    @Test
    void top10MergesFlavorAndRenameButSeparatesTypesAndSameNameProducts() throws Exception {
        order(1, 5, 1, "10", "2026-10-01 12:00:00");
        order(2, 5, 1, "10", "2026-10-03 12:00:00");
        order(3, 5, 1, "10", "2026-10-03 12:00:00");
        item(1, 1, 10L, null, "old", 2);
        item(2, 2, 10L, null, "renamed", 3);
        item(3, 3, 10L, null, "latest,one", 4);
        item(4, 3, 10L, null, "latest,two", 1);
        item(5, 1, null, 10L, "setmeal", 6);
        item(6, 1, 20L, null, "latest,two", 5);
        item(7, 1, 30L, null, "dish-thirty", 6);
        // 不符合有效订单条件及时间范围的记录都不能增加销量或替换展示名称。
        order(4, 6, 1, "10", "2026-10-03 13:00:00"); item(8, 4, 10L, null, "cancelled", 100);
        order(5, 5, 0, "10", "2026-10-03 13:00:00"); item(9, 5, 10L, null, "unpaid", 200);
        order(6, 5, 2, "10", "2026-10-03 13:00:00"); item(10, 6, 10L, null, "refunded", 300);
        order(7, 5, 1, "10", "2026-09-30 12:00:00"); item(11, 7, 10L, null, "before", 400);
        order(8, 5, 1, "10", "2026-10-04 00:00:00"); item(12, 8, 10L, null, "after", 500);
        item(13, 3, 10L, null, "zero", 0); item(14, 3, 10L, null, "negative", -1);
        item(15, 3, 10L, 10L, "both-types", 900); item(16, 3, null, null, "no-product", 900);
        JSONObject result = data("top10");
        assertEquals("latest，two,dish-thirty,setmeal,latest，two", result.getString("nameList"));
        assertEquals("10,6,6,5", result.getString("numberList"));
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(qualify("SELECT name FROM order_detail WHERE id=4"))) {
            assertTrue(rows.next()); assertEquals("latest,two", rows.getString(1));
        }
    }

    @Test
    void top10LimitsResultsAndUsesStableProductIdOrderingForTies() throws Exception {
        order(1, 5, 1, "0", "2026-10-01 12:00:00");
        for (int id = 12; id >= 1; id--) { item(id, 1, (long) id, null, "dish-" + id, 1); }
        JSONObject top = data("top10");
        assertEquals("dish-1,dish-2,dish-3,dish-4,dish-5,dish-6,dish-7,dish-8,dish-9,dish-10", top.getString("nameList"));
        assertEquals(10, top.getString("numberList").split(",").length);
    }

    @Test
    void everyRouteRejectsMissingReversedAndOverlongRanges() throws Exception {
        for (String route : List.of("turnoverStatistics", "userStatistics", "ordersStatistics", "top10")) {
            mvc.perform(get("/admin/report/" + route).header("token", token()))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.msg").value("begin和end不能为空，格式为yyyy-MM-dd"));
            mvc.perform(get("/admin/report/" + route).header("token", token())
                            .param("begin", "2026-10-03").param("end", "2026-10-01"))
                    .andExpect(jsonPath("$.code").value(0));
            mvc.perform(get("/admin/report/" + route).header("token", token())
                            .param("begin", "2024-01-01").param("end", "2025-01-01"))
                    .andExpect(jsonPath("$.msg").value("日期范围最多包含366天"));
        }
        mvc.perform(get("/admin/report/turnoverStatistics").header("token", token())
                        .param("begin", "2024-01-01").param("end", "2024-12-31"))
                .andExpect(jsonPath("$.code").value(1));
    }

    private byte[] export(String begin, String end) throws Exception {
        var request = get("/admin/report/export").header("token", token());
        if (begin != null) { request.param("begin", begin); }
        if (end != null) { request.param("end", end); }
        return mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void exportedWorkbookMatchesSqlReportsAndPreservesFullProductName() throws Exception {
        order(1, 5, 1, "10.10", "2026-10-01 00:00:00");
        order(2, 5, 1, "20.20", "2026-10-03 23:59:59");
        order(3, 1, 0, "999", "2026-10-03 12:00:00");
        item(1, 1, 10L, null, "old", 2); item(2, 2, 10L, null, "name,latest", 3);
        execute("INSERT INTO user (id,openid,create_time) VALUES (1,'old','2026-09-01 00:00:00'),"
                + "(2,'new','2026-10-03 23:59:59')");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export("2026-10-01", "2026-10-03")))) {
            assertEquals(3, book.getNumberOfSheets());
            var summary = book.getSheetAt(0);
            assertEquals(30.3, summary.getRow(3).getCell(1).getNumericCellValue(), 0.000001);
            assertEquals(3, summary.getRow(4).getCell(1).getNumericCellValue());
            assertEquals(2, summary.getRow(5).getCell(1).getNumericCellValue());
            assertEquals(0.6667, summary.getRow(6).getCell(1).getNumericCellValue());
            assertEquals(15.15, summary.getRow(7).getCell(1).getNumericCellValue());
            assertEquals(1, summary.getRow(8).getCell(1).getNumericCellValue());
            assertEquals(2, summary.getRow(9).getCell(1).getNumericCellValue());
            var daily = book.getSheetAt(1);
            assertEquals(0, daily.getRow(4).getCell(1).getNumericCellValue());
            assertEquals(1, daily.getRow(4).getCell(7).getNumericCellValue());
            assertEquals("name,latest", book.getSheetAt(2).getRow(3).getCell(3).getStringCellValue());
            assertEquals(5, book.getSheetAt(2).getRow(3).getCell(4).getNumericCellValue());
        }
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void exportDefaultsToLastThirtyCompleteDaysAndInvalidRangesReturnJson() throws Exception {
        order(1, 5, 1, "10", "2026-09-17 00:00:00");
        order(2, 5, 1, "20", "2026-10-16 23:59:59");
        order(3, 5, 1, "999", "2026-09-16 23:59:59");
        order(4, 5, 1, "888", "2026-10-17 00:00:00");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export(null, null)))) {
            assertEquals(30, book.getSheetAt(0).getRow(3).getCell(1).getNumericCellValue());
            var daily = book.getSheetAt(1);
            assertEquals(32, daily.getLastRowNum());
            assertEquals(LocalDate.of(2026, 9, 17), daily.getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(LocalDate.of(2026, 10, 16), daily.getRow(32).getCell(0).getLocalDateTimeCellValue().toLocalDate());
        }
        mvc.perform(get("/admin/report/export").header("token", token()).param("begin", "2026-10-01"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(get("/admin/report/export").header("token", token())
                        .param("begin", "2024-01-01").param("end", "2025-01-01"))
                .andExpect(jsonPath("$.msg").value("日期范围最多包含366天"));
    }

    @Test
    void exportUsesOneRepeatableReadSnapshotAcrossAllThreeQueries() throws Exception {
        order(1, 5, 1, "10", "2026-10-01 12:00:00");
        item(1, 1, 10L, null, "old-name", 1);
        afterOrderQuery = () -> {
            // 第一次一致性读之后，通过另一个连接提交数据；后两次查询不应看到新提交。
            try (var other = dataSource.getConnection(); var statement = other.createStatement()) {
                statement.executeUpdate(qualify("INSERT INTO orders (id,number,status,pay_status,user_id,address_book_id,order_time,amount) "
                        + "VALUES (2,'new-order',5,1,5,1,'2026-10-03 12:00:00',20.00)"));
                statement.executeUpdate(qualify("INSERT INTO order_detail (id,order_id,dish_id,name,number,amount) "
                        + "VALUES (2,2,10,'new-name',2,10.00)"));
                statement.executeUpdate(qualify("INSERT INTO user (id,openid,create_time) VALUES (99,'new-user','2026-10-03 12:00:00')"));
            } catch (Exception ex) { throw new RuntimeException(ex); }
        };
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export("2026-10-01", "2026-10-03")))) {
            assertEquals(1, book.getSheetAt(0).getRow(4).getCell(1).getNumericCellValue());
            assertEquals(0, book.getSheetAt(0).getRow(8).getCell(1).getNumericCellValue());
            assertEquals("old-name", book.getSheetAt(2).getRow(3).getCell(3).getStringCellValue());
            assertEquals(1, book.getSheetAt(2).getRow(3).getCell(4).getNumericCellValue());
        }
        assertEquals(1, data("userStatistics").getString("newUserList").chars().filter(c -> c == '1').count());
        assertEquals("3", data("top10").getString("numberList"));
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    private final class AfterOrderQueryInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".dailyOrders")) {
                Runnable callback = afterOrderQuery;
                afterOrderQuery = null;
                if (callback != null) { callback.run(); }
            }
            return result;
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableWebMvc
    @Import({ReportController.class, WebMvcConfiguration.class, JwtTokenAdminInterceptor.class,
            JwtTokenUserInterceptor.class, GlobalExceptionHandler.class})
    static class TestConfig { }
}
