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
import com.sky.service.AdminOrderService;
import com.sky.vo.OrderStatisticsVO;
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
    void allFiveRoutesRequireUserJwt() throws Exception {
        for (var request : List.of(post("/user/order/submit"), get("/user/order/historyOrders"),
                get("/user/order/orderDetail/1"), put("/user/order/cancel/1"), post("/user/order/repetition/1"))) {
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

    @Test
    void cancelUsesPathIdAndAcceptsOptionalReasonOrMissingBody() throws Exception {
        mvc.perform(put("/user/order/cancel/9").header("authentication", token).contentType("application/json")
                        .content("{\"id\":999,\"cancelReason\":\"不想要了\"}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).cancel(9L, "不想要了");
        mvc.perform(put("/user/order/cancel/10").header("authentication", token))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).cancel(10L, null);
        mvc.perform(put("/user/order/cancel/11").header("authentication", token).contentType("application/json").content("null"))
                .andExpect(jsonPath("$.code").value(1));
        verify(service).cancel(11L, null);
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void repetitionBindsOnlyPathOrderAndRequestId() throws Exception {
        mvc.perform(post("/user/order/repetition/9").header("authentication", token).contentType("application/json")
                        .content("{\"requestId\":\"repeat-1\",\"userId\":6,\"id\":999}"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data").isEmpty());
        verify(service).repetition(9L, "repeat-1");
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void repetitionRequiresBodyAndNewRoutesRejectMalformedInput() throws Exception {
        for (String body : new String[]{"", "null", "not-json"}) {
            mvc.perform(post("/user/order/repetition/9").header("authentication", token)
                            .contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("订单请求体缺失或格式错误"));
        }
        mvc.perform(put("/user/order/cancel/9").header("authentication", token).contentType("application/json").content("not-json"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/user/order/cancel/abc").header("authentication", token))
                .andExpect(jsonPath("$.msg").value("请求参数格式错误：id"));
        mvc.perform(post("/user/order/repetition/abc").header("authentication", token).contentType("application/json")
                        .content("{\"requestId\":\"repeat\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(service);
    }

    private String adminToken() {
        return JwtUtil.createJWT(context.getBean(JwtProperties.class).getAdminSecretKey(), 60000,
                Map.of(JwtClaimsConstant.EMP_ID, 1L));
    }

    @Test
    void allEightAdminRoutesRequireAdminJwtAndRejectUserJwt() throws Exception {
        for (var request : List.of(get("/admin/order/conditionSearch"), get("/admin/order/statistics"),
                get("/admin/order/details/9"), put("/admin/order/confirm"), put("/admin/order/delivery/9"),
                put("/admin/order/complete/9"), put("/admin/order/cancel"), put("/admin/order/rejection"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header("token", token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(context.getBean(AdminOrderService.class));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void adminQueriesBindDefaultsFiltersAndHideIdempotencyFields() throws Exception {
        AdminOrderService admin = context.getBean(AdminOrderService.class);
        when(admin.search(any())).thenReturn(new PageResult<>(0, List.of()));
        mvc.perform(get("/admin/order/conditionSearch").header("token", adminToken()))
                .andExpect(jsonPath("$.data.records").isArray());
        verify(admin).search(argThat(q -> q.getPage() == 1 && q.getPageSize() == 10));
        mvc.perform(get("/admin/order/conditionSearch").header("token", adminToken()).param("page", "2")
                        .param("pageSize", "5").param("number", "123").param("phone", "138")
                        .param("status", "2").param("beginTime", "2026-10-01 00:00:00"))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).search(argThat(q -> q.getPage() == 2 && q.getPageSize() == 5
                && "123".equals(q.getNumber()) && "138".equals(q.getPhone()) && q.getStatus() == 2
                && LocalDateTime.of(2026, 10, 1, 0, 0).equals(q.getBeginTime())));
        when(admin.statistics()).thenReturn(new OrderStatisticsVO());
        mvc.perform(get("/admin/order/statistics").header("token", adminToken()))
                .andExpect(jsonPath("$.code").value(1));
        OrderVO order = new OrderVO();
        order.setId(9L);
        order.setRequestId("hidden");
        order.setRequestHash("hidden");
        when(admin.detail(9L)).thenReturn(order);
        mvc.perform(get("/admin/order/details/9").header("token", adminToken()))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void adminWritesUseDocumentedBodiesAndPathsWithoutTrustingTargetStatus() throws Exception {
        AdminOrderService admin = context.getBean(AdminOrderService.class);
        mvc.perform(put("/admin/order/confirm").header("token", adminToken()).contentType("application/json")
                        .content("{\"id\":9,\"status\":5}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).confirm(9L);
        mvc.perform(put("/admin/order/delivery/10").header("token", adminToken()).param("id", "999"))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).delivery(10L);
        mvc.perform(put("/admin/order/complete/11").header("token", adminToken()))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).complete(11L);
        mvc.perform(put("/admin/order/cancel").header("token", adminToken()).contentType("application/json")
                        .content("{\"id\":12,\"cancelReason\":\"商品售罄\"}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).cancel(12L, "商品售罄");
        mvc.perform(put("/admin/order/rejection").header("token", adminToken()).contentType("application/json")
                        .content("{\"id\":13,\"rejectionReason\":\"无法配送\"}"))
                .andExpect(jsonPath("$.code").value(1));
        verify(admin).reject(13L, "无法配送");
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void adminRejectsMalformedQueryAndBodiesBeforeService() throws Exception {
        for (String path : List.of("confirm", "cancel", "rejection")) {
            for (String body : new String[]{"", "null", "not-json"}) {
                mvc.perform(put("/admin/order/" + path).header("token", adminToken())
                                .contentType("application/json").content(body))
                        .andExpect(jsonPath("$.msg").value("订单请求体缺失或格式错误"));
            }
        }
        mvc.perform(get("/admin/order/conditionSearch").header("token", adminToken()).param("page", "bad"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/order/conditionSearch").header("token", adminToken()).param("beginTime", "bad"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/order/details/bad").header("token", adminToken()))
                .andExpect(jsonPath("$.msg").value("请求参数格式错误：id"));
        verifyNoInteractions(context.getBean(AdminOrderService.class));
        assertNull(BaseContext.getCurrentId());
    }

    @Configuration
    @EnableWebMvc
    @Import({WebMvcConfiguration.class, JwtTokenAdminInterceptor.class, JwtTokenUserInterceptor.class,
            GlobalExceptionHandler.class, com.sky.controller.user.OrderController.class,
            com.sky.controller.admin.OrderController.class})
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
        @Bean AdminOrderService adminOrderService() { return mock(AdminOrderService.class); }
    }
}
