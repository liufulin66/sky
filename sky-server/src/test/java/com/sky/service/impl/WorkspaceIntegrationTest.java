package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.controller.admin.WorkspaceController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.mapper.WorkspaceMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.WorkspaceService;
import com.sky.utils.JwtUtil;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实建表结构、Mapper、Service、HTTP鉴权；仅创建和清理随机前缀测试表。 */
@EnabledIfSystemProperty(named = "workspace.mysql.tests", matches = "true")
class WorkspaceIntegrationTest {
    private final String tablePrefix = "ws_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)(\\b(?:from|join|into|update|table)\\s+)(`?)(orders|user|dish|setmeal)\\b\\2");
    private final List<String> createdTables = new ArrayList<>();
    private Connection connection;
    private GenericWebApplicationContext context;
    private MockMvc mvc;

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
        connection = source.getConnection();
        String baseline = Files.readString(Path.of("../sky-take-out.sql"));
        for (String table : List.of("orders", "user", "dish", "setmeal")) {
            var match = Pattern.compile("(?s)CREATE TABLE `" + table + "`.*?;").matcher(baseline);
            assertTrue(match.find(), "Missing baseline table " + table);
            execute(match.group());
            createdTables.add(tablePrefix + table);
        }
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(
                new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/WorkspaceMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            String xml = qualify(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        WorkspaceMapper mapper = template.getMapper(WorkspaceMapper.class);
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new AnnotatedBeanDefinitionReader(context).register(TestConfig.class);
        context.registerBean(WorkspaceService.class, () -> new WorkspaceServiceImpl(mapper,
                Clock.fixed(Instant.parse("2026-10-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))));
        context.registerBean(JwtProperties.class, () -> {
            JwtProperties jwt = new JwtProperties();
            jwt.setAdminTokenName("token"); jwt.setAdminSecretKey("workspace-integration-admin-secret-32-bytes");
            jwt.setUserTokenName("authentication"); jwt.setUserSecretKey("workspace-integration-user-secret-32-bytes");
            return jwt;
        });
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId();
        if (context != null) { context.close(); }
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

    private JSONObject data(String route) throws Exception {
        String token = JwtUtil.createJWT(context.getBean(JwtProperties.class).getAdminSecretKey(), 60000,
                Map.of(JwtClaimsConstant.EMP_ID, 1L));
        String json = mvc.perform(get("/admin/workspace/" + route).header("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(BaseContext.getCurrentId());
        return JSON.parseObject(json).getJSONObject("data");
    }

    private void order(int id, int status, int paid, String amount, String time) throws Exception {
        String sql = qualify("INSERT INTO orders (id,number,status,pay_status,user_id,address_book_id,order_time,amount) VALUES (?,?,?,?,5,1,?,?)");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id); statement.setString(2, "workspace-" + id);
            statement.setInt(3, status); statement.setInt(4, paid);
            statement.setString(5, time); statement.setBigDecimal(6, new BigDecimal(amount));
            statement.executeUpdate();
        }
    }

    @Test
    void allEmptyTablesReturnNumericZeros() throws Exception {
        for (String route : List.of("businessData", "overviewOrders", "overviewDishes", "overviewSetmeals")) {
            JSONObject result = data(route);
            for (String field : result.keySet()) { assertEquals(0, result.getBigDecimal(field).signum(), route + "." + field); }
        }
    }

    @Test
    void businessAndOverviewUseTodayOrderTimeButOnlyCompletedPaidOrdersEarnRevenue() throws Exception {
        order(1, 5, 1, "10.10", "2026-10-17 00:00:00");
        order(2, 5, 1, "20.20", "2026-10-17 23:59:59");
        order(3, 5, 0, "999.00", "2026-10-17 12:00:00");
        order(4, 5, 2, "888.00", "2026-10-17 12:00:00");
        order(5, 1, 0, "500.00", "2026-10-17 12:00:00");
        order(6, 6, 0, "600.00", "2026-10-17 12:00:00");
        order(7, 2, 1, "700.00", "2026-10-17 12:00:00");
        order(8, 3, 1, "800.00", "2026-10-17 12:00:00");
        order(9, 4, 1, "900.00", "2026-10-17 12:00:00");
        order(10, 5, 1, "9999.00", "2026-10-16 23:59:59");
        order(11, 5, 1, "9999.00", "2026-10-18 00:00:00");
        execute("UPDATE orders SET delivery_time='2026-10-17 12:00:00' WHERE id=10");
        execute("INSERT INTO user (id,openid,create_time) VALUES (1,'u1','2026-10-17 00:00:00'),"
                + "(2,'u2','2026-10-17 23:59:59'),(3,'u3','2026-10-16 23:59:59'),(4,'u4','2026-10-18 00:00:00')");
        JSONObject business = data("businessData");
        assertEquals(0, new BigDecimal("30.30").compareTo(business.getBigDecimal("turnover")));
        assertEquals(2, business.getIntValue("validOrderCount"));
        assertEquals(0.2222, business.getDoubleValue("orderCompletionRate"));
        assertEquals(15.15, business.getDoubleValue("unitPrice"));
        assertEquals(2, business.getIntValue("newUsers"));
        JSONObject orders = data("overviewOrders");
        assertEquals(9, orders.getIntValue("allOrders"));
        assertEquals(1, orders.getIntValue("waitingOrders"));
        assertEquals(1, orders.getIntValue("deliveredOrders"));
        assertEquals(4, orders.getIntValue("completedOrders"));
        assertEquals(1, orders.getIntValue("cancelledOrders"));
    }

    @Test
    void newUsersAreCountedEvenWhenNoOrdersExist() throws Exception {
        execute("INSERT INTO user (id,openid,create_time) VALUES (1,'today','2026-10-17 08:00:00'),"
                + "(2,'yesterday','2026-10-16 08:00:00')");
        JSONObject business = data("businessData");
        assertEquals(1, business.getIntValue("newUsers"));
        assertEquals(0.0, business.getDoubleValue("orderCompletionRate"));
        assertEquals(0.0, business.getDoubleValue("unitPrice"));
    }

    @Test
    void productOverviewCountsOwnStatusAcrossAllDatesWithoutUserVisibilityJoins() throws Exception {
        execute("INSERT INTO dish (id,name,category_id,price,status,create_time) VALUES "
                + "(1,'old-dish',999,10.00,1,'2020-01-01 00:00:00'),"
                + "(2,'new-dish',999,10.00,1,'2026-10-17 00:00:00'),"
                + "(3,'stopped-dish',999,10.00,0,'2026-10-17 00:00:00')");
        execute("INSERT INTO setmeal (id,name,category_id,price,status) VALUES "
                + "(1,'on',999,10.00,1),(2,'off-one',999,10.00,0),(3,'off-two',999,10.00,0)");
        JSONObject dishes = data("overviewDishes");
        assertEquals(2, dishes.getIntValue("sold")); assertEquals(1, dishes.getIntValue("discontinued"));
        JSONObject setmeals = data("overviewSetmeals");
        assertEquals(1, setmeals.getIntValue("sold")); assertEquals(2, setmeals.getIntValue("discontinued"));
    }

    @Test
    void aggregateMoneyCanExceedSingleOrderMaximumAndZeroPriceOrdersAreValid() throws Exception {
        order(1, 5, 1, "99999999.99", "2026-10-17 12:00:00");
        order(2, 5, 1, "99999999.99", "2026-10-17 12:00:00");
        order(3, 5, 1, "0.00", "2026-10-17 12:00:00");
        JSONObject business = data("businessData");
        assertEquals(0, new BigDecimal("199999999.98").compareTo(business.getBigDecimal("turnover")));
        assertEquals(3, business.getIntValue("validOrderCount"));
        assertEquals(1.0, business.getDoubleValue("orderCompletionRate"));
        assertEquals(66666666.66, business.getDoubleValue("unitPrice"));
    }

    @Configuration
    @EnableWebMvc
    @Import({WorkspaceController.class, WebMvcConfiguration.class, JwtTokenAdminInterceptor.class,
            JwtTokenUserInterceptor.class, GlobalExceptionHandler.class})
    static class TestConfig { }
}
