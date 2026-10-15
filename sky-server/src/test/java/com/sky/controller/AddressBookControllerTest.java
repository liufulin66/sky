package com.sky.controller;

import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.properties.JwtProperties;
import com.sky.service.AddressBookService;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AddressBookControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AddressBookService service;
    private String token;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        service = context.getBean(AddressBookService.class);
        token = JwtUtil.createJWT(context.getBean(JwtProperties.class).getUserSecretKey(), 60000,
                Map.of(JwtClaimsConstant.USER_ID, 5L));
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (context != null) { context.close(); }
    }

    @Test
    void allSevenEndpointsRequireUserJwt() throws Exception {
        for (var request : List.of(get("/user/addressBook/list"), post("/user/addressBook"),
                get("/user/addressBook/1"), put("/user/addressBook"), delete("/user/addressBook?id=1"),
                put("/user/addressBook/default"), get("/user/addressBook/default"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/user/addressBook/list").header("authentication", "invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void confirmedRoutesBindParametersAndReturnExpectedResult() throws Exception {
        AddressBook address = AddressBook.builder().id(1L).userId(5L).consignee("张三").isDefault(0).build();
        when(service.list()).thenReturn(List.of(address));
        when(service.getById(1L)).thenReturn(address);
        mvc.perform(get("/user/addressBook/list").header("authentication", token))
                .andExpect(jsonPath("$.data[0].id").value(1));
        mvc.perform(post("/user/addressBook").header("authentication", token).contentType("application/json")
                        .content("{\"consignee\":\"张三\",\"phone\":\"13800138000\",\"detail\":\"示例路\",\"userId\":6,\"isDefault\":1}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).save(argThat(dto -> "张三".equals(dto.getConsignee())));
        mvc.perform(get("/user/addressBook/1").header("authentication", token))
                .andExpect(jsonPath("$.data.consignee").value("张三"));
        mvc.perform(put("/user/addressBook").header("authentication", token).contentType("application/json")
                        .content("{\"id\":1,\"consignee\":\"李四\"}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).update(argThat(dto -> Long.valueOf(1).equals(dto.getId())));
        mvc.perform(delete("/user/addressBook").param("id", "1").header("authentication", token))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).delete(1L);
        mvc.perform(put("/user/addressBook/default").header("authentication", token).contentType("application/json")
                        .content("{\"id\":1}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).setDefault(1L);
        mvc.perform(get("/user/addressBook/default").header("authentication", token))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data").isEmpty());
        verify(service).getDefault();
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void missingMalformedAndNullBodiesAreBusinessErrors() throws Exception {
        for (var request : List.of(post("/user/addressBook"), put("/user/addressBook"), put("/user/addressBook/default"))) {
            for (String body : new String[]{"", "null", "not-json"}) {
                mvc.perform(request.header("authentication", token).contentType("application/json").content(body))
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.msg").value("地址簿请求体缺失或格式错误"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void nonNumericIdsReturnParameterErrors() throws Exception {
        mvc.perform(get("/user/addressBook/abc").header("authentication", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("请求参数格式错误：id"));
        mvc.perform(delete("/user/addressBook").param("id", "abc").header("authentication", token))
                .andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(service);
    }

    @Configuration
    @EnableWebMvc
    @Import({WebMvcConfiguration.class, JwtTokenAdminInterceptor.class, JwtTokenUserInterceptor.class,
            GlobalExceptionHandler.class, com.sky.controller.user.AddressBookController.class})
    static class TestConfig {
        @Bean JwtProperties jwtProperties() {
            JwtProperties jwt = new JwtProperties();
            jwt.setUserTokenName("authentication");
            jwt.setUserSecretKey("address-test-user-secret-key-32-bytes");
            jwt.setAdminTokenName("token");
            jwt.setAdminSecretKey("address-test-admin-secret-key-32-bytes");
            return jwt;
        }
        @Bean AddressBookService addressBookService() { return mock(AddressBookService.class); }
    }
}
