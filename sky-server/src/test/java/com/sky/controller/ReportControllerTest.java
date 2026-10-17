package com.sky.controller;

import com.sky.config.WebMvcConfiguration;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.controller.admin.ReportController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.properties.JwtProperties;
import com.sky.service.ReportService;
import com.sky.dto.ReportExportFileDTO;
import com.sky.exception.ReportBusinessException;
import org.springframework.http.ContentDisposition;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ReportService service;
    private JwtProperties jwt;
    private final List<String> routes = List.of("turnoverStatistics", "userStatistics", "ordersStatistics", "top10");

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(TestConfig.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        service = context.getBean(ReportService.class); jwt = context.getBean(JwtProperties.class);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId(); if (context != null) { context.close(); }
    }

    private String token() {
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), 60000, Map.of(JwtClaimsConstant.EMP_ID, 1L));
    }

    @Test
    void allFourRoutesRequireAdminJwt() throws Exception {
        String user = JwtUtil.createJWT(jwt.getUserSecretKey(), 60000, Map.of(JwtClaimsConstant.USER_ID, 5L));
        for (String route : List.of("turnoverStatistics", "userStatistics", "ordersStatistics", "top10", "export")) {
            mvc.perform(get("/admin/report/" + route)).andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/report/" + route).header("token", "bad")).andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/report/" + route).header("token", user)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service); assertNull(BaseContext.getCurrentId());
    }

    @Test
    void datesAndExistingCsvFieldsAreBoundForAllRoutes() throws Exception {
        var begin = LocalDate.of(2026, 10, 1); var end = begin.plusDays(1);
        when(service.turnoverStatistics(any(), any())).thenReturn(new TurnoverReportVO("2026-10-01,2026-10-02", "1.00,0.00"));
        when(service.userStatistics(any(), any())).thenReturn(new UserReportVO("2026-10-01,2026-10-02", "2,2", "1,0"));
        when(service.ordersStatistics(any(), any())).thenReturn(new OrderReportVO("2026-10-01,2026-10-02", "1,1", "1,0", 2, 1, 0.5));
        when(service.top10(any(), any())).thenReturn(new SalesTop10ReportVO("dish", "2"));
        for (String route : routes) {
            var response = mvc.perform(get("/admin/report/" + route).header("token", token())
                    .param("begin", begin.toString()).param("end", end.toString()).param("userId", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
            if (route.equals("top10")) {
                response.andExpect(jsonPath("$.data.nameList").value("dish")).andExpect(jsonPath("$.data.numberList").value("2"));
            } else { response.andExpect(jsonPath("$.data.dateList").value("2026-10-01,2026-10-02")); }
        }
        verify(service).turnoverStatistics(begin, end); verify(service).userStatistics(begin, end);
        verify(service).ordersStatistics(begin, end); verify(service).top10(begin, end);
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void malformedOrImpossibleDatesDoNotReachService() throws Exception {
        for (String route : List.of("turnoverStatistics", "userStatistics", "ordersStatistics", "top10", "export")) {
            for (String invalid : List.of("bad", "2026-02-30", "2026-13-01", "2026-10-01 00:00:00")) {
                mvc.perform(get("/admin/report/" + route).header("token", token())
                                .param("begin", invalid).param("end", "2026-10-03"))
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.msg").value("请求参数格式错误：begin"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void exportReturnsRawBytesWithUtf8FilenameAndSupportsOptionalDates() throws Exception {
        byte[] bytes = {80, 75, 3, 4, 0, 1, 2};
        String filename = "运营报表_2026-10-01_2026-10-03.xlsx";
        when(service.exportExcel(any(), any())).thenReturn(new ReportExportFileDTO(filename, bytes));
        var response = mvc.perform(get("/admin/report/export").header("token", token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes)).andExpect(header().string("Content-Length", "7"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertEquals(filename, ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
        verify(service).exportExcel(null, null);
        mvc.perform(get("/admin/report/export").header("token", token()).param("begin", "2026-10-01").param("end", "2026-10-03"))
                .andExpect(content().bytes(bytes));
        verify(service).exportExcel(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void exportValidationErrorsRemainJsonWithoutDownloadHeaders() throws Exception {
        when(service.exportExcel(isNull(), eq(LocalDate.of(2026, 10, 3))))
                .thenThrow(new ReportBusinessException("begin和end不能为空，格式为yyyy-MM-dd"));
        mvc.perform(get("/admin/report/export").header("token", token()).param("end", "2026-10-03"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("begin和end不能为空，格式为yyyy-MM-dd"))
                .andExpect(header().doesNotExist("Content-Disposition"));
        assertNull(BaseContext.getCurrentId());
    }

    @Configuration
    @EnableWebMvc
    @Import({ReportController.class, WebMvcConfiguration.class, JwtTokenAdminInterceptor.class,
            JwtTokenUserInterceptor.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean ReportService reportService() { return mock(ReportService.class); }
        @Bean JwtProperties jwtProperties() {
            JwtProperties jwt = new JwtProperties();
            jwt.setAdminTokenName("token"); jwt.setAdminSecretKey("report-controller-admin-secret-32-bytes");
            jwt.setUserTokenName("authentication"); jwt.setUserSecretKey("report-controller-user-secret-32-bytes");
            return jwt;
        }
    }
}
