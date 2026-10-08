package com.sky.interceptor;

import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * jwt令牌校验的拦截器（管理端）
 */
@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 校验jwt
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断当前拦截到的是Controller的方法还是其他资源
        if (!(handler instanceof HandlerMethod)) {
            // 当前拦截到的不是动态方法，直接放行
            return true;
        }

        // 1、从请求头中获取令牌（请求头名称从配置读取：admin-token-name）
        String token = request.getHeader(jwtProperties.getAdminTokenName());

        // 2、校验令牌
        try {
            // 注意：完整令牌相当于通行证，真实项目不应整条打进日志，这里只截取前缀便于调试
            log.info("jwt校验:{}", token == null ? "null" : token.substring(0, Math.min(20, token.length())) + "...");

            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);

            // ⚠ claims 中的数字被 jjwt 反序列化为 Integer，不能强转 (Long)，要先转字符串再转 Long
            Long empId = Long.valueOf(claims.get(JwtClaimsConstant.EMP_ID).toString());
            log.info("当前员工id：{}", empId);

            // 将当前登录人id存入 ThreadLocal，供 Service/Mapper 层通过 BaseContext.getCurrentId() 使用
            BaseContext.setCurrentId(empId);

            // 3、通过，放行
            return true;
        } catch (Exception ex) {
            // 4、不通过，响应401状态码（前端约定：见到401跳转登录页）
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    /**
     * 请求处理完成后清理 ThreadLocal
     * 必须清理：Tomcat 线程是池化复用的，不清理会把身份"泄漏"给下一个请求
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
