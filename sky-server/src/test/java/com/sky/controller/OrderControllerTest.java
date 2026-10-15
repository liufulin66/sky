package com.sky.controller;

import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.properties.JwtProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.JwtUtil;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private OrderService service;
    private String token;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        service = context.getBean(OrderService.class);
        token = JwtUtil.createJWT(context.getBean(JwtProperties.class).getUserSecretKey(), 60000,
                Map.of(JwtClaimsConstant.USER_ID, 5L));
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (context != null) { context.close(); }
    }

    @Test
    void allThreeRoutesRequireUserJwt() throws Exception {
        for (var request : List.of(post("/user/order/submit"), get("/user/order/historyOrders"),
                get("/user/order/orderDetail/1"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/user/order/historyOrders").header("authentication", "bad-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void routesBindRequestIdPaginationAndReservationTime() throws Exception {
        when(service.submit(any())).thenReturn(OrderSubmitVO.builder().id(9L).orderNumber("number")
                .orderAmount(new BigDecimal("50.00")).orderTime(LocalDateTime.of(2026, 10, 15, 12, 0)).build());
        mvc.perform(post("/user/order/submit").header("authentication", token).contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"addressBookId\":1,\"payMethod\":1,\"amount\":50,"
                                + "\"deliveryStatus\":0,\"estimatedDeliveryTime\":\"2030-01-01 12:00:00\",\"userId\":6}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.id").value(9));
        verify(service).submit(argThat(d -> "req-1".equals(d.getRequestId())
                && LocalDateTime.of(2030, 1, 1, 12, 0).equals(d.getEstimatedDeliveryTime())));
        when(service.historyOrders(1, 10, null)).thenReturn(new PageResult<>(0, List.of()));
        mvc.perform(get("/user/order/historyOrders").header("authentication", token).param("userId", "6"))
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.records").isArray());
        when(service.historyOrders(2, 5, 1)).thenReturn(new PageResult<>(0, List.of()));
        mvc.perform(get("/user/order/historyOrders").header("authentication", token)
                        .param("page", "2").param("pageSize", "5").param("status", "1"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).historyOrders(2, 5, 1);
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void detailHidesIdempotencyInternals() throws Exception {
        OrderVO order = new OrderVO();
        order.setId(9L);
        order.setRequestId("private-request");
        order.setRequestHash("private-hash");
        order.setDeliveryAmount(new BigDecimal("0.00"));
        when(service.orderDetail(9L)).thenReturn(order);
        mvc.perform(get("/user/order/orderDetail/9").header("authentication", token))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.deliveryAmount").value(0))
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
    }

    @Test
    void malformedBodiesAndNonNumericParametersHaveSpecificErrors() throws Exception {
        for (String body : new String[]{"", "null", "not-json"}) {
            mvc.perform(post("/user/order/submit").header("authentication", token).contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("下单请求体缺失或格式错误"));
        }
        mvc.perform(get("/user/order/orderDetail/abc").header("authentication", token))
                .andExpect(jsonPath("$.msg").value("请求参数格式错误：id"));
        mvc.perform(get("/user/order/historyOrders").param("page", "abc").header("authentication", token))
                .andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(service);
    }

    @Configuration
    @EnableWebMvc
    @Import({WebMvcConfiguration.class, JwtTokenAdminInterceptor.class, JwtTokenUserInterceptor.class,
            GlobalExceptionHandler.class, com.sky.controller.user.OrderController.class})
    static class TestConfig {
        @Bean JwtProperties jwtProperties() {
            JwtProperties jwt = new JwtProperties();
            jwt.setUserTokenName("authentication");
            jwt.setUserSecretKey("order-test-user-secret-key-32-bytes");
            jwt.setAdminTokenName("token");
            jwt.setAdminSecretKey("order-test-admin-secret-key-32-bytes");
            return jwt;
        }
        @Bean OrderService orderService() { return mock(OrderService.class); }
    }
}
