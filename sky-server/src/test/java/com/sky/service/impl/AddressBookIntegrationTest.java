package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.AddressBookDTO;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.AddressBookService;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 只创建和清理随机前缀测试表，验证生产SQL、真实Spring事务和用户行锁。 */
@EnabledIfSystemProperty(named = "address.mysql.tests", matches = "true")
class AddressBookIntegrationTest {
    private final String tablePrefix = "addr_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)(\\b(?:from|join|into|update|table)\\s+)(`?)(address_book|user)\\b\\2");
    private final List<String> createdTables = new ArrayList<>();
    private Connection fixtureConnection;
    private AnnotationConfigApplicationContext context;
    private AddressBookService service;

    @BeforeEach
    void setUp() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-dev.yml"));
        Properties props = yaml.getObject();
        String prefix = "sky.datasource.";
        String url = "jdbc:mysql://" + props.getProperty(prefix + "host") + ":" + props.getProperty(prefix + "port")
                + "/" + props.getProperty(prefix + "database")
                + "?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                props.getProperty(prefix + "username"), props.getProperty(prefix + "password"));
        fixtureConnection = dataSource.getConnection();
        execute("CREATE TABLE user (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        execute("CREATE TABLE address_book (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, "
                + "consignee VARCHAR(50), phone VARCHAR(11) NOT NULL, sex VARCHAR(2), "
                + "province_code VARCHAR(12), province_name VARCHAR(32), city_code VARCHAR(12), city_name VARCHAR(32), "
                + "district_code VARCHAR(12), district_name VARCHAR(32), detail VARCHAR(200), label VARCHAR(100), "
                + "is_default TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute("INSERT INTO user VALUES (5),(6)");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/UserMapper.xml", "mapper/AddressBookMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                String xml = qualifyTables(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                        configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(UserMapper.class, () -> template.getMapper(UserMapper.class));
        context.registerBean(AddressBookMapper.class, () -> template.getMapper(AddressBookMapper.class));
        context.refresh();
        service = context.getBean(AddressBookService.class);
        BaseContext.setCurrentId(5L);
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
        return TABLE_REFERENCE.matcher(input).replaceAll(match -> match.group(1) + "`" + tablePrefix + match.group(3) + "`");
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = fixtureConnection.createStatement()) {
            statement.execute(qualifyTables(sql));
            if (sql.startsWith("CREATE TABLE ")) {
                createdTables.add(tablePrefix + sql.substring("CREATE TABLE ".length()).split(" ", 2)[0]);
            }
        }
    }

    private AddressBookDTO form(String name) {
        AddressBookDTO dto = new AddressBookDTO();
        dto.setConsignee(name);
        dto.setPhone("13800138000");
        dto.setSex("1");
        dto.setProvinceCode("110000");
        dto.setProvinceName("北京市");
        dto.setCityCode("110100");
        dto.setCityName("北京市");
        dto.setDistrictCode("110101");
        dto.setDistrictName("东城区");
        dto.setDetail("示例路1号");
        dto.setLabel("家");
        return dto;
    }

    private Long create(String name) {
        service.save(form(name));
        return service.list().stream().filter(a -> name.equals(a.getConsignee())).findFirst().orElseThrow().getId();
    }

    @Test
    void lifecycleKeepsDefaultExplicitAndAllowsClearingOptionalFields() {
        assertTrue(service.list().isEmpty());
        assertNull(service.getDefault());
        Long first = create("张三");
        Long second = create("李四");
        assertNull(service.getDefault());
        assertEquals(second, service.list().get(0).getId());
        service.setDefault(first);
        service.setDefault(first); // 重复设置同一个地址也成功。
        assertEquals(first, service.list().get(0).getId());
        AddressBookDTO edited = form("王五");
        edited.setId(first);
        edited.setLabel(null);
        edited.setProvinceCode(null);
        service.update(edited);
        service.update(edited); // 相同内容重复保存不应误报不存在。
        AddressBook address = service.getById(first);
        assertEquals("王五", address.getConsignee());
        assertEquals(1, address.getIsDefault());
        assertNull(address.getLabel());
        assertNull(address.getProvinceCode());
        assertEquals("东城区", address.getDistrictName());
        service.setDefault(second);
        assertEquals(0, service.getById(first).getIsDefault());
        service.delete(second);
        assertNull(service.getDefault());
        assertEquals(1, service.list().size());
        assertEquals(0, service.list().get(0).getIsDefault());
    }

    @Test
    void foreignAddressCannotBeReadChangedDeletedOrMadeDefault() {
        Long own = create("本人的地址");
        service.setDefault(own);
        BaseContext.setCurrentId(6L);
        Long other = create("别人的地址");
        service.setDefault(other);
        BaseContext.setCurrentId(5L);
        assertThrows(AddressBookBusinessException.class, () -> service.getById(other));
        assertThrows(AddressBookBusinessException.class, () -> service.delete(other));
        assertThrows(AddressBookBusinessException.class, () -> service.setDefault(other));
        AddressBookDTO edited = form("伪造地址");
        edited.setId(other);
        assertThrows(AddressBookBusinessException.class, () -> service.update(edited));
        assertEquals(own, service.getDefault().getId());
        assertEquals(1, service.list().size());
        BaseContext.setCurrentId(6L);
        assertEquals(other, service.getDefault().getId());
        assertEquals("别人的地址", service.getById(other).getConsignee());
    }

    @Test
    void failureAfterClearingDefaultRollsBackTheWholeTransaction() throws Exception {
        Long first = create("旧默认");
        Long second = create("新默认");
        service.setDefault(first);
        // 测试表约束仅拒绝目标默认写入，强制触发“取消旧默认之后”的事务失败。
        execute("ALTER TABLE address_book ADD CONSTRAINT reject_target CHECK (consignee != '新默认' OR is_default = 0)");
        assertThrows(RuntimeException.class, () -> service.setDefault(second));
        assertEquals(first, service.getDefault().getId());
        assertEquals(0, service.getById(second).getIsDefault());
    }

    @Test
    void concurrentDefaultsLeaveExactlyOneDefaultPerUser() throws Exception {
        List<Long> ids = List.of(create("地址一"), create("地址二"));
        BaseContext.setCurrentId(6L);
        Long other = create("用户六");
        service.setDefault(other);
        BaseContext.setCurrentId(5L);
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Long id = ids.get(i % ids.size());
                futures.add(executor.submit(() -> {
                    try {
                        BaseContext.setCurrentId(5L);
                        start.await();
                        service.setDefault(id);
                        return null;
                    } finally { BaseContext.removeCurrentId(); }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) { future.get(20, TimeUnit.SECONDS); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, service.list().stream().filter(a -> a.getIsDefault() == 1).count());
        BaseContext.setCurrentId(6L);
        assertEquals(other, service.getDefault().getId());
    }

    @Configuration
    @EnableTransactionManagement
    @Import(AddressBookServiceImpl.class)
    static class TestConfig { }
}
