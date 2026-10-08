package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置类，注册web层相关组件
 *
 * 注意：这里实现的是 WebMvcConfigurer 接口（而不是教程里的 extends WebMvcConfigurationSupport）。
 * WebMvcConfigurationSupport 会让 Spring Boot 的 MVC 自动配置整体退避
 * （自动配置类上有 @ConditionalOnMissingBean(WebMvcConfigurationSupport.class)），
 * 导致消息转换器、静态资源处理等默认配置失效——教程后来要手写资源处理器才能打开 doc.html，根源就在这。
 * 实现 WebMvcConfigurer 则是在自动配置之上"做加法"，不破坏任何默认行为。
 */
@Configuration
@Slf4j
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    /**
     * 注册自定义拦截器
     *
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenAdminInterceptor)
                // 拦截管理端所有请求
                .addPathPatterns("/admin/**")
                // 登录接口必须放行，否则"没登录就无法去登录"
                .excludePathPatterns("/admin/employee/login");
    }
}
