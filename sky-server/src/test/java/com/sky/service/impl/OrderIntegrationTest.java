package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.PageInterceptor;
import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.exception.OrderBusinessException;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.mapper.*;
import com.sky.properties.JwtProperties;
import com.sky.properties.OrderProperties;
import com.sky.service.ShopService;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 执行生产迁移和Mapper；仅创建、清理随机前缀测试表，HTTP分派、事务及并发均使用真实实现。 */
@EnabledIfSystemProperty(named = "order.mysql.tests", matches = "true")
class OrderIntegrationTest {
    private final String tablePrefix = "ord_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)(\\b(?:from|join|into|update|table)\\s+)(`?)(address_book|order_detail|orders|shopping_cart|setmeal_dish|setmeal|category|dish|user)\\b\\2");
    private final List<String> createdTables = new ArrayList<>();
    private Connection fixtureConnection;
    private GenericWebApplicationContext context;
    private MockMvc mvc;
    private ShopService shop;
    private OrderProperties fees;

    @BeforeEach
    void setUp() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-dev.yml"));
        Properties props = yaml.getObject();
        String prefix = "sky.datasource.";
        String url = "jdbc:mysql://" + props.getProperty(prefix + "host") + ":" + props.getProperty(prefix + "port")
                + "/" + props.getProperty(prefix + "database")
                + "?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000";
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                props.getProperty(prefix + "username"), props.getProperty(prefix + "password"));
        fixtureConnection = source.getConnection();
        execute("CREATE TABLE user (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        execute("CREATE TABLE category (id BIGINT PRIMARY KEY, type INT, status INT) ENGINE=InnoDB");
        String baseline = Files.readString(Path.of("../sky-take-out.sql"));
        for (String table : List.of("dish", "setmeal", "setmeal_dish", "address_book", "shopping_cart", "orders", "order_detail")) {
            var match = Pattern.compile("(?s)CREATE TABLE `" + table + "`.*?;").matcher(baseline);
            assertTrue(match.find(), "Missing baseline DDL for " + table);
            execute(match.group());
        }
        String migration = Files.readString(Path.of("../sql/migrations/2026-10-15-user-orders.sql"))
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String sql : migration.split(";")) {
            if (!sql.isBlank()) { execute(sql.trim()); }
        }
        execute("INSERT INTO user VALUES (5),(6)");
        execute("INSERT INTO category VALUES (1,1,1),(2,2,1)");
        execute("INSERT INTO dish (id,name,category_id,price,image,status) VALUES "
                + "(10,'fresh-dish',1,25.00,'fresh-image',1),(11,'second-dish',1,10.00,'image',1)");
        execute("INSERT INTO setmeal (id,name,category_id,price,status) VALUES (20,'fresh-setmeal',2,40.00,1)");
        execute("INSERT INTO setmeal_dish (setmeal_id,dish_id,copies) VALUES (20,10,1),(20,11,2)");
        execute("INSERT INTO address_book (id,user_id,consignee,phone,province_name,detail,is_default) VALUES "
                + "(1,5,'receiver-five','13800138000','province','street',0),"
                + "(2,6,'receiver-six','13900139000','province','street',0)");

        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(
                new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        PageInterceptor paging = new PageInterceptor();
        Properties pagingProperties = new Properties();
        pagingProperties.setProperty("helperDialect", "mysql");
        paging.setProperties(pagingProperties);
        config.addInterceptor(paging);
        for (String resource : List.of("User", "AddressBook", "ShoppingCart", "Dish", "Setmeal", "Order", "OrderDetail")) {
            String name = "mapper/" + resource + "Mapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
                String xml = qualifyTables(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                        config, name, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new AnnotatedBeanDefinitionReader(context).register(TestConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(UserMapper.class, () -> template.getMapper(UserMapper.class));
        context.registerBean(AddressBookMapper.class, () -> template.getMapper(AddressBookMapper.class));
        context.registerBean(ShoppingCartMapper.class, () -> template.getMapper(ShoppingCartMapper.class));
        context.registerBean(DishMapper.class, () -> template.getMapper(DishMapper.class));
        context.registerBean(SetmealMapper.class, () -> template.getMapper(SetmealMapper.class));
        context.registerBean(OrderMapper.class, () -> template.getMapper(OrderMapper.class));
        context.registerBean(OrderDetailMapper.class, () -> template.getMapper(OrderDetailMapper.class));
        shop = mock(ShopService.class); // 营业状态是独立的Redis存储，这里只隔离外部Redis。
        fees = new OrderProperties();
        context.registerBean(ShopService.class, () -> shop);
        context.registerBean(OrderProperties.class, () -> fees);
        context.registerBean(JwtProperties.class, () -> {
            JwtProperties jwt = new JwtProperties();
            jwt.setUserTokenName("authentication");
            jwt.setUserSecretKey("order-integration-user-secret-32-bytes");
            jwt.setAdminTokenName("token");
            jwt.setAdminSecretKey("order-integration-admin-secret-32-bytes");
            return jwt;
        });
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId();
        if (context != null) { context.close(); }
        if (fixtureConnection != null) {
            try {
                for (String table : createdTables) {
                    if (!table.startsWith(tablePrefix) || !table.matches("[a-z0-9_]+")) {
                        throw new IllegalStateException("Unexpected fixture table name");
                    }
                    try (Statement statement = fixtureConnection.createStatement()) {
                        statement.execute("DROP TABLE `" + table + "`");
                    }
                }
            } finally { fixtureConnection.close(); }
        }
    }

    private String qualifyTables(String input) {
        return TABLE_REFERENCE.matcher(input).replaceAll(m -> m.group(1) + "`" + tablePrefix + m.group(3) + "`");
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = fixtureConnection.createStatement()) {
            statement.execute(qualifyTables(sql));
            var match = Pattern.compile("(?i)^CREATE TABLE `?(\\w+)`?").matcher(sql);
            if (match.find()) { createdTables.add(tablePrefix + match.group(1)); }
        }
    }

    private long count(String table) throws Exception {
        try (Statement statement = fixtureConnection.createStatement();
             var rows = statement.executeQuery(qualifyTables("SELECT COUNT(*) FROM " + table))) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void cart(long userId) throws Exception {
        execute("INSERT INTO shopping_cart (user_id,dish_id,name,number,amount) VALUES (" + userId + ",10,'stale-name',2,1.00)");
    }

    private JSONObject form(String key, long userId) {
        JSONObject form = new JSONObject();
        form.put("requestId", key);
        form.put("addressBookId", userId == 5 ? 1 : 2);
        form.put("payMethod", 1);
        form.put("amount", new BigDecimal("50.00"));
        return form;
    }

    private String token(long userId) {
        return JwtUtil.createJWT(context.getBean(JwtProperties.class).getUserSecretKey(), 60000,
                Map.of(JwtClaimsConstant.USER_ID, userId));
    }

    private JSONObject submit(long userId, JSONObject form, int expectedCode) throws Exception {
        String json = mvc.perform(post("/user/order/submit").header("authentication", token(userId))
                        .contentType("application/json").content(form.toJSONString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(BaseContext.getCurrentId());
        return JSON.parseObject(json);
    }

    private JSONObject detail(long userId, long orderId, int expectedCode) throws Exception {
        return JSON.parseObject(mvc.perform(get("/user/order/orderDetail/" + orderId).header("authentication", token(userId)))
                .andExpect(jsonPath("$.code").value(expectedCode)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void submitPersistsSnapshotsAndReplayDoesNotConsumeNewCartOrNeedOpenShop() throws Exception {
        cart(5);
        JSONObject form = form("replay-key", 5);
        form.put("userId", 6);
        form.put("status", 5);
        JSONObject original = submit(5, form, 1).getJSONObject("data");
        long id = original.getLongValue("id");
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_detail"));
        assertEquals(0, count("shopping_cart"));
        execute("UPDATE dish SET name='changed',price=99,status=0 WHERE id=10");
        execute("UPDATE address_book SET consignee='changed',detail='changed' WHERE id=1");
        cart(5);
        doThrow(new OrderBusinessException("店铺已打烊")).when(shop).checkOpenForNewOrder();
        assertEquals(original, submit(5, form, 1).getJSONObject("data"));
        assertEquals(1, count("shopping_cart"));
        JSONObject data = detail(5, id, 1).getJSONObject("data");
        assertEquals("receiver-five", data.getString("consignee"));
        assertEquals("provincestreet", data.getString("address"));
        assertEquals(1, data.getIntValue("status"));
        assertEquals(0, data.getIntValue("payStatus"));
        assertFalse(data.containsKey("requestId"));
        assertFalse(data.containsKey("requestHash"));
        JSONObject item = data.getJSONArray("orderDetailList").getJSONObject(0);
        assertEquals("fresh-dish", item.getString("name"));
        assertEquals(0, item.getBigDecimal("amount").compareTo(new BigDecimal("25.00")));
        assertEquals(2, item.getIntValue("number"));
        form.put("remark", "changed-request");
        submit(5, form, 0);
        assertEquals(1, count("orders"));
    }

    @Test
    void foreignAddressPriceMismatchAndUnavailableProductsLeaveCartUntouched() throws Exception {
        cart(5);
        JSONObject form = form("reject-key", 5);
        form.put("addressBookId", 2);
        submit(5, form, 0);
        form.put("addressBookId", 1);
        form.put("amount", new BigDecimal("2.00"));
        submit(5, form, 0);
        form.put("amount", new BigDecimal("50.00"));
        execute("UPDATE category SET status=0 WHERE id=1");
        submit(5, form, 0);
        execute("UPDATE category SET status=1 WHERE id=1");
        execute("UPDATE shopping_cart SET dish_id=NULL,setmeal_id=20,number=1 WHERE user_id=5");
        form.put("amount", new BigDecimal("40.00"));
        execute("UPDATE dish SET status=0 WHERE id=11");
        submit(5, form, 0);
        assertEquals(0, count("orders"));
        assertEquals(1, count("shopping_cart"));
    }

    @Test
    void failedDetailInsertRollsBackHeaderAndRequestIdCanBeRetried() throws Exception {
        cart(5);
        String constraint = "reject_detail_" + UUID.randomUUID().toString().substring(0, 8);
        execute("ALTER TABLE order_detail ADD CONSTRAINT " + constraint + " CHECK (number < 0)");
        submit(5, form("rollback-key", 5), 0);
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_detail"));
        assertEquals(1, count("shopping_cart"));
        execute("ALTER TABLE order_detail DROP CHECK " + constraint);
        submit(5, form("rollback-key", 5), 1);
        assertEquals(1, count("orders"));
    }

    @Test
    void failedCartDeletionRollsBackHeaderAndDetails() throws Exception {
        cart(5);
        String trigger = tablePrefix + "reject_cart";
        execute("CREATE TRIGGER `" + trigger + "` BEFORE DELETE ON `" + tablePrefix
                + "shopping_cart` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'");
        submit(5, form("cleanup-failure", 5), 0);
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_detail"));
        assertEquals(1, count("shopping_cart"));
        execute("DROP TRIGGER `" + trigger + "`");
    }

    @Test
    void concurrentSameRequestCreatesOneOrderAndReturnsSameResult() throws Exception {
        cart(5);
        var executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JSONObject>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return submit(5, form("concurrent-key", 5), 1).getJSONObject("data");
                }));
            }
            start.countDown();
            JSONObject first = futures.get(0).get(20, TimeUnit.SECONDS);
            for (var future : futures) { assertEquals(first, future.get(20, TimeUnit.SECONDS)); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_detail"));
        assertEquals(0, count("shopping_cart"));
    }

    @Test
    void historyPaginationAndDetailsAreRestrictedToCurrentUser() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            cart(5);
            ids.add(submit(5, form("history-" + i, 5), 1).getJSONObject("data").getLong("id"));
        }
        cart(6);
        long other = submit(6, form("history-0", 6), 1).getJSONObject("data").getLongValue("id");
        execute("UPDATE orders SET status=2 WHERE id=" + ids.get(0));
        String json = mvc.perform(get("/user/order/historyOrders").header("authentication", token(5))
                        .param("page", "2").param("pageSize", "1").param("status", "1").param("userId", "6"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(ids.get(1)))
                .andExpect(jsonPath("$.data.records[0].orderDetailList[0].number").value(2))
                .andExpect(jsonPath("$.data.records[0].requestHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, JSON.parseObject(json).getJSONObject("data").getJSONArray("records").size());
        detail(5, other, 0);
        detail(6, ids.get(0), 0);
        mvc.perform(get("/user/order/historyOrders").header("authentication", token(5)).param("page", "100"))
                .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void feesReservationAndLongAddressFitMigratedSchema() throws Exception {
        fees.setPackAmount(1);
        fees.setDeliveryAmount(new BigDecimal("2.50"));
        execute("UPDATE address_book SET consignee='" + "张".repeat(50) + "',province_name='" + "省".repeat(32)
                + "',city_name='" + "市".repeat(32) + "',district_name='" + "区".repeat(32)
                + "',detail='" + "路".repeat(200) + "' WHERE id=1");
        cart(5);
        JSONObject input = form("fees-key", 5);
        input.put("amount", new BigDecimal("53.50"));
        input.put("packAmount", 1);
        input.put("deliveryStatus", 0);
        input.put("estimatedDeliveryTime", LocalDateTime.now().plusDays(1).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        input.put("tablewareStatus", 0);
        input.put("tablewareNumber", 3);
        long id = submit(5, input, 1).getJSONObject("data").getLongValue("id");
        JSONObject order = detail(5, id, 1).getJSONObject("data");
        assertEquals(50, order.getString("consignee").length());
        assertEquals(296, order.getString("address").length());
        assertEquals(0, order.getBigDecimal("deliveryAmount").compareTo(new BigDecimal("2.50")));
        assertEquals(1, order.getIntValue("packAmount"));
        assertEquals(3, order.getIntValue("tablewareNumber"));
    }

    @Test
    void mixedDishesFlavorsAndSetmealBecomeSeparateSnapshotLines() throws Exception {
        cart(5);
        execute("INSERT INTO shopping_cart (user_id,dish_id,dish_flavor,name,number,amount) VALUES (5,10,'mild','stale',1,1.00)");
        execute("INSERT INTO shopping_cart (user_id,setmeal_id,name,number,amount) VALUES (5,20,'stale',1,1.00)");
        JSONObject input = form("mixed-key", 5);
        input.put("amount", new BigDecimal("115.00"));
        long id = submit(5, input, 1).getJSONObject("data").getLongValue("id");
        var lines = detail(5, id, 1).getJSONObject("data").getJSONArray("orderDetailList");
        assertEquals(3, lines.size());
        assertEquals(1, lines.stream().map(JSONObject.class::cast).filter(d -> "mild".equals(d.getString("dishFlavor"))).count());
        JSONObject setmeal = lines.stream().map(JSONObject.class::cast).filter(d -> d.getLongValue("setmealId") == 20)
                .findFirst().orElseThrow();
        assertEquals("fresh-setmeal", setmeal.getString("name"));
        assertEquals(0, setmeal.getBigDecimal("amount").compareTo(new BigDecimal("40.00")));
        assertEquals(0, count("shopping_cart"));
    }

    @Test
    void differentRequestIdsCannotSettleTheSameCartTwice() throws Exception {
        cart(5);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String key = "distinct-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    String json = mvc.perform(post("/user/order/submit").header("authentication", token(5))
                                    .contentType("application/json").content(form(key, 5).toJSONString()))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertNull(BaseContext.getCurrentId());
                    return JSON.parseObject(json).getIntValue("code");
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) { successes += future.get(20, TimeUnit.SECONDS); }
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_detail"));
    }

    @Test
    void databaseRejectsDuplicateOrderNumberAndPerUserRequestKey() throws Exception {
        cart(5);
        long id = submit(5, form("unique-key", 5), 1).getJSONObject("data").getLongValue("id");
        cart(5);
        long second = submit(5, form("second-key", 5), 1).getJSONObject("data").getLongValue("id");
        assertThrows(SQLException.class, () -> execute("UPDATE orders SET request_id='unique-key' WHERE id=" + second));
        assertThrows(SQLException.class, () -> execute("UPDATE orders o JOIN orders first_order ON first_order.id="
                + id + " SET o.number=first_order.number WHERE o.id=" + second));
        assertEquals(2, count("orders"));
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @Import({WebMvcConfiguration.class, JwtTokenAdminInterceptor.class, JwtTokenUserInterceptor.class,
            GlobalExceptionHandler.class, OrderServiceImpl.class, com.sky.controller.user.OrderController.class})
    static class TestConfig { }
}
