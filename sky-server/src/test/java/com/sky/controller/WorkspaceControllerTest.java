package com.sky.controller;

import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.controller.admin.WorkspaceController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.properties.JwtProperties;
import com.sky.service.WorkspaceService;
import com.sky.utils.JwtUtil;
import com.sky.vo.*;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private WorkspaceService service;
    private JwtProperties jwt;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        service = context.getBean(WorkspaceService.class);
        jwt = context.getBean(JwtProperties.class);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (context != null) { context.close(); }
    }

    private String adminToken() {
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), 60000, Map.of(JwtClaimsConstant.EMP_ID, 1L));
    }

    @Test
    void fourRoutesRequireAdminJwtAndDoNotAcceptUserJwt() throws Exception {
        String user = JwtUtil.createJWT(jwt.getUserSecretKey(), 60000, Map.of(JwtClaimsConstant.USER_ID, 5L));
        for (String route : List.of("businessData", "overviewOrders", "overviewDishes", "overviewSetmeals")) {
            mvc.perform(get("/admin/workspace/" + route)).andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/workspace/" + route).header("token", "invalid"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/workspace/" + route).header("token", user))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void routesReturnFrontendFieldNamesWithoutAcceptingClientDateOrUserFilters() throws Exception {
        when(service.businessData()).thenReturn(BusinessDataVO.builder().turnover(30.05).validOrderCount(3)
                .orderCompletionRate(0.4286).unitPrice(10.02).newUsers(1).build());
        mvc.perform(get("/admin/workspace/businessData").header("token", adminToken())
                        .param("begin", "2000-01-01").param("userId", "999"))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.turnover").value(30.05))
                .andExpect(jsonPath("$.data.validOrderCount").value(3))
                .andExpect(jsonPath("$.data.orderCompletionRate").value(0.4286))
                .andExpect(jsonPath("$.data.unitPrice").value(10.02)).andExpect(jsonPath("$.data.newUsers").value(1));
        when(service.overviewOrders()).thenReturn(new OrderOverViewVO(1, 2, 3, 4, 12));
        mvc.perform(get("/admin/workspace/overviewOrders").header("token", adminToken()))
                .andExpect(jsonPath("$.data.waitingOrders").value(1))
                .andExpect(jsonPath("$.data.deliveredOrders").value(2))
                .andExpect(jsonPath("$.data.completedOrders").value(3))
                .andExpect(jsonPath("$.data.cancelledOrders").value(4))
                .andExpect(jsonPath("$.data.allOrders").value(12));
        when(service.overviewDishes()).thenReturn(new DishOverViewVO(3, 1));
        mvc.perform(get("/admin/workspace/overviewDishes").header("token", adminToken()))
                .andExpect(jsonPath("$.data.sold").value(3)).andExpect(jsonPath("$.data.discontinued").value(1));
        when(service.overviewSetmeals()).thenReturn(new SetmealOverViewVO(1, 2));
        mvc.perform(get("/admin/workspace/overviewSetmeals").header("token", adminToken()))
                .andExpect(jsonPath("$.data.sold").value(1)).andExpect(jsonPath("$.data.discontinued").value(2));
        verify(service).businessData(); verify(service).overviewOrders();
        verify(service).overviewDishes(); verify(service).overviewSetmeals();
        assertNull(BaseContext.getCurrentId());
    }

    @Configuration
    @EnableWebMvc
    @Import({WorkspaceController.class, WebMvcConfiguration.class, JwtTokenAdminInterceptor.class,
            JwtTokenUserInterceptor.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean WorkspaceService workspaceService() { return mock(WorkspaceService.class); }
        @Bean JwtProperties jwtProperties() {
            JwtProperties jwt = new JwtProperties();
            jwt.setAdminTokenName("token"); jwt.setAdminSecretKey("workspace-controller-admin-secret-32-bytes");
            jwt.setUserTokenName("authentication"); jwt.setUserSecretKey("workspace-controller-user-secret-32-bytes");
            return jwt;
        }
    }
}
