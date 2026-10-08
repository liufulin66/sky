package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

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

    /**
     * 扩展 Spring MVC 框架的消息转换器：
     * 用配置了日期格式的 JsonMapper（JacksonObjectMapper.create()）替换默认的 Jackson 消息转换器，
     * 使返回给前端的 LocalDateTime / LocalDate / LocalTime 按指定格式输出
     * （默认输出 ISO-8601 格式如 2026-10-08T15:30:00，带 "T"，前端不好看也不好解析）
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("扩展消息转换器...");

        // Jackson 3（Spring Boot 4）的 JSON 消息转换器：传入自定义 JsonMapper 后按原样使用该 mapper
        JacksonJsonHttpMessageConverter converter =
                new JacksonJsonHttpMessageConverter(JacksonObjectMapper.create());

        // 就地替换默认的 Jackson 转换器——保持它原有的位置和顺序。
        // 注意：教程写法是 converters.add(0, converter)，把 Jackson 转换器插到列表最前面；
        // 在 Boot 4 下这会让它抢在 StringHttpMessageConverter 之前处理 String 返回值，
        // 使"本身已是 JSON 字符串的响应"被二次转义（Boot 4.0.1 的更新说明记录过这个坑），
        // 所以这里改为"找到默认转换器、原地替换"，其余转换器顺序完全不动。
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof JacksonJsonHttpMessageConverter) {
                converters.set(i, converter);
                return;
            }
        }

        // 兜底：未找到默认 Jackson 转换器时（正常情况下不会发生）追加到末尾
        converters.add(converter);
    }
}
