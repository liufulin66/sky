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
            "(?i)(\\b(?:from|join|into|update|table)\\s+)(`?)(address_book|order_repetition|order_detail|orders|shopping_cart|setmeal_dish|setmeal|category|dish|user)\\b\\2");
    private final List<String> createdTables = new ArrayList<>();
    private Connection fixtureConnection;
    private GenericWebApplicationContext context;
    private MockMvc mvc;
    private ShopService shop;
    private OrderProperties fees;
    private DriverManagerDataSource dataSource;

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
        dataSource = source;
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
        String repetitionMigration = Files.readString(Path.of("../sql/migrations/2026-10-16-order-repetition.sql"))
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String sql : repetitionMigration.split(";")) {
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
        for (String resource : List.of("User", "AddressBook", "ShoppingCart", "Dish", "Setmeal", "Order", "OrderDetail", "OrderRepetition")) {
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
        context.registerBean(OrderRepetitionMapper.class, () -> template.getMapper(OrderRepetitionMapper.class));
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

    private void cancel(long userId, long orderId, String body, int expectedCode) throws Exception {
        var request = put("/user/order/cancel/" + orderId).header("authentication", token(userId));
        if (body != null) { request.contentType("application/json").content(body); }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(expectedCode));
        assertNull(BaseContext.getCurrentId());
    }

    private JSONObject repetition(long userId, long orderId, String key, int expectedCode) throws Exception {
        String json = mvc.perform(post("/user/order/repetition/" + orderId).header("authentication", token(userId))
                        .contentType("application/json").content(JSON.toJSONString(Map.of("requestId", key))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(BaseContext.getCurrentId());
        return JSON.parseObject(json);
    }

    private com.alibaba.fastjson.JSONArray cartRows(long userId) throws Exception {
        return JSON.parseObject(mvc.perform(get("/user/shoppingCart/list").header("authentication", token(userId)))
                .andExpect(jsonPath("$.code").value(1)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .getJSONArray("data");
    }

    private long sourceOrder(long userId, String key) throws Exception {
        cart(userId);
        return submit(userId, form(key, userId), 1).getJSONObject("data").getLongValue("id");
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

    @Test
    void cancellationPreservesSnapshotsAndIsIdempotentWithoutRestoringCart() throws Exception {
        long id = sourceOrder(5, "cancel-source");
        cart(5);
        var before = cartRows(5);
        cancel(6, id, null, 0);
        cancel(5, id, "{\"cancelReason\":\" 不想要了 \",\"id\":999}", 1);
        JSONObject cancelled = detail(5, id, 1).getJSONObject("data");
        assertEquals(6, cancelled.getIntValue("status"));
        assertEquals(0, cancelled.getIntValue("payStatus"));
        assertEquals("不想要了", cancelled.getString("cancelReason"));
        assertNotNull(cancelled.getString("cancelTime"));
        execute("UPDATE orders SET cancel_time='2026-01-01 00:00:00' WHERE id=" + id);
        cancel(5, id, "{\"cancelReason\":\"第二次原因\"}", 1);
        JSONObject replay = detail(5, id, 1).getJSONObject("data");
        assertEquals("不想要了", replay.getString("cancelReason"));
        assertTrue(replay.getString("cancelTime").startsWith("2026-01-01"));
        assertEquals(before, cartRows(5));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_detail"));
    }

    @Test
    void cancellationRejectsPaidAndOtherStatesAndAcceptsMissingReason() throws Exception {
        long id = sourceOrder(5, "cancel-states");
        for (int status = 1; status <= 6; status++) {
            execute("UPDATE orders SET status=" + status + ",pay_status=1 WHERE id=" + id);
            cancel(5, id, null, 0);
        }
        for (int status = 2; status <= 5; status++) {
            execute("UPDATE orders SET status=" + status + ",pay_status=0 WHERE id=" + id);
            cancel(5, id, null, 0);
        }
        execute("UPDATE orders SET status=1,pay_status=0 WHERE id=" + id);
        cancel(5, id, null, 1);
        assertEquals("用户主动取消", detail(5, id, 1).getJSONObject("data").getString("cancelReason"));
        cancel(5, 999999, null, 0);
    }

    @Test
    void cancellationAndPaymentStyleConditionalUpdateCannotBothWin() throws Exception {
        long id = sourceOrder(5, "cancel-race");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var cancellation = executor.submit(() -> {
                start.await();
                String json = mvc.perform(put("/user/order/cancel/" + id).header("authentication", token(5)))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                return JSON.parseObject(json).getIntValue("code");
            });
            var payment = executor.submit(() -> {
                start.await();
                // 仅在测试表模拟条件更新竞争，不提供模拟付款的业务接口。
                try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                    return statement.executeUpdate(qualifyTables("UPDATE orders SET status=2,pay_status=1 WHERE id="
                            + id + " AND status=1 AND pay_status=0"));
                }
            });
            start.countDown();
            assertEquals(1, cancellation.get(20, TimeUnit.SECONDS) + payment.get(20, TimeUnit.SECONDS));
            JSONObject finalOrder = detail(5, id, 1).getJSONObject("data");
            assertTrue((finalOrder.getIntValue("status") == 6 && finalOrder.getIntValue("payStatus") == 0)
                    || (finalOrder.getIntValue("status") == 2 && finalOrder.getIntValue("payStatus") == 1));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void repetitionMergesCurrentCartAndRefreshesPriceWithoutChangingOrder() throws Exception {
        long id = sourceOrder(5, "repeat-source");
        cart(5);
        execute("UPDATE dish SET price=30,name='current-name',image='current-image' WHERE id=10");
        repetition(6, id, "foreign", 0);
        repetition(5, id, "repeat-op", 1);
        JSONObject row = cartRows(5).getJSONObject(0);
        assertEquals(4, row.getIntValue("number"));
        assertEquals("current-name", row.getString("name"));
        assertEquals("current-image", row.getString("image"));
        assertEquals(0, row.getBigDecimal("amount").compareTo(new BigDecimal("30.00")));
        assertEquals(1, count("order_repetition"));
        JSONObject old = detail(5, id, 1).getJSONObject("data");
        assertEquals(1, old.getIntValue("status"));
        assertEquals("fresh-dish", old.getJSONArray("orderDetailList").getJSONObject(0).getString("name"));
        execute("DELETE FROM shopping_cart WHERE user_id=5");
        execute("UPDATE dish SET status=0 WHERE id=10");
        repetition(5, id, "repeat-op", 1);
        assertTrue(cartRows(5).isEmpty());
        assertEquals(1, count("orders"));
    }

    @Test
    void cancelledOrdersCanBeRepeatedAndRequestIdCannotChangeSource() throws Exception {
        long first = sourceOrder(5, "first-source");
        long second = sourceOrder(5, "second-source");
        cancel(5, first, null, 1);
        doThrow(new OrderBusinessException("店铺已打烊")).when(shop).checkOpenForNewOrder();
        repetition(5, first, "op-one", 1);
        repetition(5, second, "op-one", 0);
        repetition(5, first, "op-two", 1);
        assertEquals(4, cartRows(5).getJSONObject(0).getIntValue("number"));
        assertEquals(2, count("order_repetition"));
    }

    @Test
    void unavailableSecondItemRollsBackFirstMergeAndFailedKeyCanBeRetried() throws Exception {
        long id = sourceOrder(5, "rollback-source");
        execute("INSERT INTO order_detail (order_id,dish_id,name,number,amount) VALUES (" + id + ",11,'second-dish',1,10.00)");
        cart(5);
        execute("UPDATE dish SET price=30 WHERE id=10");
        execute("UPDATE dish SET status=0 WHERE id=11");
        var before = cartRows(5);
        JSONObject failed = repetition(5, id, "retry-op", 0);
        assertTrue(failed.getString("msg").contains("second-dish"));
        assertEquals(before, cartRows(5)); // 数量、名称和价格刷新都回滚。
        assertEquals(0, count("order_repetition"));
        execute("UPDATE dish SET status=1 WHERE id=11");
        repetition(5, id, "retry-op", 1);
        assertEquals(2, cartRows(5).size());
        assertEquals(1, count("order_repetition"));
    }

    @Test
    void repetitionOverflowAndFailedOperationRecordRollBackAllCartChanges() throws Exception {
        long id = sourceOrder(5, "overflow-source");
        cart(5);
        execute("UPDATE shopping_cart SET number=2147483646 WHERE user_id=5");
        var before = cartRows(5);
        repetition(5, id, "overflow-op", 0);
        assertEquals(before, cartRows(5));
        assertEquals(0, count("order_repetition"));
        execute("UPDATE shopping_cart SET number=1 WHERE user_id=5");
        before = cartRows(5);
        String constraint = "reject_repeat_" + UUID.randomUUID().toString().substring(0, 8);
        execute("ALTER TABLE order_repetition ADD CONSTRAINT " + constraint + " CHECK (order_id < 0)");
        repetition(5, id, "record-failure", 0);
        assertEquals(before, cartRows(5));
        assertEquals(0, count("order_repetition"));
    }

    @Test
    void concurrentRepetitionOnlyAppendsOnceAndDifferentUsersCanReuseKey() throws Exception {
        long id = sourceOrder(5, "concurrent-repeat-source");
        var executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    repetition(5, id, "same-op", 1);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) { future.get(20, TimeUnit.SECONDS); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, cartRows(5).getJSONObject(0).getIntValue("number"));
        long other = sourceOrder(6, "other-repeat-source");
        repetition(6, other, "same-op", 1);
        assertEquals(2, cartRows(6).getJSONObject(0).getIntValue("number"));
        assertEquals(2, count("order_repetition"));
    }

    private String adminToken() {
        return JwtUtil.createJWT(context.getBean(JwtProperties.class).getAdminSecretKey(), 60000,
                Map.of(JwtClaimsConstant.EMP_ID, 1L));
    }

    private JSONObject admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             int expectedCode) throws Exception {
        String json = mvc.perform(request.header("token", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(BaseContext.getCurrentId());
        return JSON.parseObject(json);
    }

    private JSONObject adminDetail(long id) throws Exception {
        return admin(get("/admin/order/details/" + id), 1).getJSONObject("data");
    }

    private long adminFixture(int state, int pay, long user) throws Exception {
        // 仅在随机前缀测试表准备订单，不在业务中开放模拟支付接口。
        execute("INSERT INTO orders (number,status,pay_status,user_id,address_book_id,order_time,amount) VALUES ('"
                + UUID.randomUUID().toString().replace("-", "") + "'," + state + "," + pay + "," + user
                + "," + (user == 5 ? 1 : 2) + ",'2026-01-01 12:00:00',50.00)");
        try (Statement statement = fixtureConnection.createStatement();
             var rows = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void adminStatisticsIncludeHistoricalPendingOrdersAndEmptyTableReturnsZeros() throws Exception {
        JSONObject empty = admin(get("/admin/order/statistics"), 1).getJSONObject("data");
        assertEquals(0, empty.getIntValue("toBeConfirmed"));
        assertEquals(0, empty.getIntValue("confirmed"));
        assertEquals(0, empty.getIntValue("deliveryInProgress"));
        adminFixture(2, 1, 5); adminFixture(2, 1, 6);
        adminFixture(3, 1, 5); adminFixture(4, 1, 6);
        adminFixture(1, 0, 5); adminFixture(5, 1, 5); adminFixture(6, 0, 5);
        JSONObject counts = admin(get("/admin/order/statistics"), 1).getJSONObject("data");
        assertEquals(2, counts.getIntValue("toBeConfirmed"));
        assertEquals(1, counts.getIntValue("confirmed"));
        assertEquals(1, counts.getIntValue("deliveryInProgress"));
    }

    @Test
    void adminSearchPaginatesAllUsersCombinesFiltersAndReadsHistoricalDetails() throws Exception {
        long one = sourceOrder(5, "admin-search-five");
        long two = sourceOrder(6, "admin-search-six");
        execute("UPDATE orders SET order_time='2026-01-01 12:00:00' WHERE id in (" + one + "," + two + ")");
        execute("UPDATE orders SET number='search-123-five' WHERE id=" + one);
        execute("UPDATE orders SET number='search-123-six',status=2,pay_status=1 WHERE id=" + two);
        execute("UPDATE dish SET name='new-product-name',price=100 WHERE id=10");
        JSONObject page = admin(get("/admin/order/conditionSearch").param("pageSize", "1")
                .param("status", "0").param("userId", "999"), 1).getJSONObject("data");
        assertEquals(2, page.getIntValue("total"));
        assertEquals(two, page.getJSONArray("records").getJSONObject(0).getLongValue("id"));
        JSONObject next = admin(get("/admin/order/conditionSearch").param("pageSize", "1").param("page", "2"), 1)
                .getJSONObject("data").getJSONArray("records").getJSONObject(0);
        assertEquals(one, next.getLongValue("id"));
        assertEquals("fresh-dish×2", next.getString("orderDishes"));
        assertEquals(new BigDecimal("25.00"), next.getJSONArray("orderDetailList").getJSONObject(0).getBigDecimal("amount"));
        assertFalse(next.containsKey("requestId"));
        JSONObject filtered = admin(get("/admin/order/conditionSearch").param("number", "123")
                .param("phone", "139").param("status", "2")
                .param("beginTime", "2026-01-01 12:00:00").param("endTime", "2026-01-01 12:00:00"), 1)
                .getJSONObject("data");
        assertEquals(1, filtered.getIntValue("total"));
        assertEquals(two, filtered.getJSONArray("records").getJSONObject(0).getLongValue("id"));
        JSONObject beyond = admin(get("/admin/order/conditionSearch").param("page", "100"), 1).getJSONObject("data");
        assertEquals(2, beyond.getIntValue("total"));
        assertTrue(beyond.getJSONArray("records").isEmpty());
        assertEquals("fresh-dish", adminDetail(two).getJSONArray("orderDetailList").getJSONObject(0).getString("name"));
        detail(5, two, 0); // 商家可跨用户查看，用户端仍然禁止越权。
    }

    @Test
    void adminSearchTreatsSqlWildcardsLiterallyAndRejectsBadQueries() throws Exception {
        long id = adminFixture(1, 0, 5);
        adminFixture(1, 0, 6);
        execute("UPDATE orders SET number='literal_!%value' WHERE id=" + id);
        for (String search : List.of("_", "!", "%", "_!%")) {
            JSONObject data = admin(get("/admin/order/conditionSearch").param("number", search), 1).getJSONObject("data");
            assertEquals(1, data.getIntValue("total"));
            assertEquals(id, data.getJSONArray("records").getJSONObject(0).getLongValue("id"));
        }
        assertEquals(0, admin(get("/admin/order/conditionSearch").param("number", "' OR 1=1 --"), 1)
                .getJSONObject("data").getIntValue("total"));
        admin(get("/admin/order/conditionSearch").param("page", "0"), 0);
        admin(get("/admin/order/conditionSearch").param("pageSize", "101"), 0);
        admin(get("/admin/order/conditionSearch").param("status", "7"), 0);
        admin(get("/admin/order/conditionSearch").param("beginTime", "2026-02-01 00:00:00")
                .param("endTime", "2026-01-01 00:00:00"), 0);
        admin(get("/admin/order/details/999999"), 0);
    }

    @Test
    void adminPaidLifecycleOnlyChangesStatusAndCompletionTimeAndRetriesAreSafe() throws Exception {
        long id = sourceOrder(5, "admin-flow");
        execute("UPDATE orders SET status=2,pay_status=1 WHERE id=" + id);
        cart(5);
        JSONObject before = adminDetail(id);
        admin(put("/admin/order/confirm").contentType("application/json").content("{\"id\":" + id + ",\"status\":5}"), 1);
        assertEquals(3, adminDetail(id).getIntValue("status"));
        admin(put("/admin/order/confirm").contentType("application/json").content("{\"id\":" + id + "}"), 1);
        admin(put("/admin/order/complete/" + id), 0); // 不能跳过派送。
        admin(put("/admin/order/delivery/" + id), 1);
        admin(put("/admin/order/delivery/" + id), 1);
        admin(put("/admin/order/confirm").contentType("application/json").content("{\"id\":" + id + "}"), 0);
        admin(put("/admin/order/complete/" + id), 1);
        assertNotNull(adminDetail(id).getString("deliveryTime"));
        execute("UPDATE orders SET delivery_time='2026-01-02 12:00:00' WHERE id=" + id);
        admin(put("/admin/order/complete/" + id), 1);
        JSONObject after = adminDetail(id);
        assertEquals(5, after.getIntValue("status"));
        assertEquals("2026-01-02 12:00", after.getString("deliveryTime"));
        assertEquals(LocalDateTime.of(2026, 1, 2, 12, 0),
                context.getBean(OrderMapper.class).getByIdForAdmin(id).getDeliveryTime());
        assertEquals(1, after.getIntValue("payStatus"));
        assertEquals(before.getBigDecimal("amount"), after.getBigDecimal("amount"));
        assertEquals(before.getJSONArray("orderDetailList"), after.getJSONArray("orderDetailList"));
        assertEquals(1, cartRows(5).size());
        assertEquals(2, cartRows(5).getJSONObject(0).getIntValue("number"));
    }

    @Test
    void adminRejectsUnpaidTransitionsAndDoesNotFakeRefundOnRejectionOrCancellation() throws Exception {
        long id = adminFixture(2, 0, 5);
        admin(put("/admin/order/confirm").contentType("application/json").content("{\"id\":" + id + "}"), 0);
        execute("UPDATE orders SET status=3 WHERE id=" + id);
        admin(put("/admin/order/delivery/" + id), 0);
        execute("UPDATE orders SET status=4 WHERE id=" + id);
        admin(put("/admin/order/complete/" + id), 0);
        execute("UPDATE orders SET status=2,pay_status=1 WHERE id=" + id);
        JSONObject before = adminDetail(id);
        JSONObject rejected = admin(put("/admin/order/rejection").contentType("application/json")
                .content("{\"id\":" + id + ",\"rejectionReason\":\"售罄\"}"), 0);
        assertTrue(rejected.getString("msg").contains("暂未接入退款"));
        JSONObject cancelled = admin(put("/admin/order/cancel").contentType("application/json")
                .content("{\"id\":" + id + ",\"cancelReason\":\"售罄\"}"), 0);
        assertTrue(cancelled.getString("msg").contains("暂未接入退款"));
        assertEquals(before, adminDetail(id));
        admin(put("/admin/order/rejection").contentType("application/json")
                .content("{\"id\":999999,\"rejectionReason\":\"售罄\"}"), 0);
    }

    @Test
    void adminCancellationPreservesCartDetailsAndFirstReasonAndUserCancellationCanReplay() throws Exception {
        long id = sourceOrder(6, "admin-cancel");
        cart(6);
        JSONObject before = adminDetail(id);
        admin(put("/admin/order/cancel").contentType("application/json")
                .content("{\"id\":" + id + ",\"cancelReason\":\" 商品售罄 \"}"), 1);
        execute("UPDATE orders SET cancel_time='2026-01-01 12:00:00' WHERE id=" + id);
        admin(put("/admin/order/cancel").contentType("application/json")
                .content("{\"id\":" + id + ",\"cancelReason\":\"第二次原因\"}"), 1);
        cancel(6, id, null, 1);
        JSONObject after = adminDetail(id);
        assertEquals(6, after.getIntValue("status"));
        assertEquals(0, after.getIntValue("payStatus"));
        assertEquals("商品售罄", after.getString("cancelReason"));
        assertEquals("2026-01-01 12:00", after.getString("cancelTime"));
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 0),
                context.getBean(OrderMapper.class).getByIdForAdmin(id).getCancelTime());
        assertEquals(before.getJSONArray("orderDetailList"), after.getJSONArray("orderDetailList"));
        assertEquals(1, cartRows(6).size());
    }

    @Test
    void concurrentAdminCompletionRequestsPreserveOneResultAndNeverRegressState() throws Exception {
        long id = adminFixture(4, 1, 5);
        var executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return admin(put("/admin/order/complete/" + id), 1).getIntValue("code");
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) { assertEquals(1, future.get(20, TimeUnit.SECONDS)); }
            JSONObject completed = adminDetail(id);
            assertEquals(5, completed.getIntValue("status"));
            assertNotNull(completed.getString("deliveryTime"));
            admin(put("/admin/order/delivery/" + id), 0);
            assertEquals(completed, adminDetail(id));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentUserAndAdminCancellationKeepOneReasonWithoutDeadlock() throws Exception {
        long id = sourceOrder(5, "admin-user-cancel-race");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> user = executor.submit(() -> {
                start.await();
                cancel(5, id, null, 1);
                return null;
            });
            Future<?> merchant = executor.submit(() -> {
                start.await();
                return admin(put("/admin/order/cancel").contentType("application/json")
                        .content("{\"id\":" + id + ",\"cancelReason\":\"商家主动取消\"}"), 1);
            });
            start.countDown(); user.get(20, TimeUnit.SECONDS); merchant.get(20, TimeUnit.SECONDS);
            JSONObject after = adminDetail(id);
            assertEquals(6, after.getIntValue("status"));
            assertTrue(List.of("用户主动取消", "商家主动取消").contains(after.getString("cancelReason")));
            assertNotNull(after.getString("cancelTime"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @Import({WebMvcConfiguration.class, JwtTokenAdminInterceptor.class, JwtTokenUserInterceptor.class,
            GlobalExceptionHandler.class, OrderServiceImpl.class, AdminOrderServiceImpl.class, ShoppingCartServiceImpl.class,
            com.sky.controller.admin.OrderController.class,
            com.sky.controller.user.OrderController.class, com.sky.controller.user.ShoppingCartController.class})
    static class TestConfig { }
}
