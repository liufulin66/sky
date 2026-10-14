package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.JwtProperties;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.JwtUtil;
import com.sky.vo.UserLoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    private static final String CODE_TO_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatProperties weChatProperties;

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getCode())) {
            throw new LoginFailedException("登录凭证不能为空");
        }

        String openid = getOpenid(dto.getCode());
        User user = userMapper.getByOpenid(openid);
        if (user == null) {
            User newUser = User.builder().openid(openid).createTime(LocalDateTime.now()).build();
            try {
                userMapper.insert(newUser);
                user = newUser;
            } catch (DuplicateKeyException ex) {
                // 唯一索引阻止并发注册重复用户；本方法不加事务，重查可看到已提交的用户。
                user = userMapper.getByOpenid(openid);
                if (user == null) {
                    throw ex;
                }
            }
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);
        return UserLoginVO.builder().id(user.getId()).openid(user.getOpenid()).token(token).build();
    }

    private String getOpenid(String code) {
        if (!StringUtils.hasText(weChatProperties.getAppid())
                || !StringUtils.hasText(weChatProperties.getSecret())) {
            log.error("微信登录配置缺失");
            throw new LoginFailedException("微信登录暂不可用");
        }

        Map<String, String> params = new HashMap<>();
        params.put("appid", weChatProperties.getAppid());
        params.put("secret", weChatProperties.getSecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");

        try {
            JSONObject result = JSON.parseObject(HttpClientUtil.doGet(CODE_TO_SESSION_URL, params));
            if (result == null) {
                throw new LoginFailedException("微信登录返回异常，请重新登录");
            }
            Integer errcode = result.getInteger("errcode");
            if (errcode != null && errcode != 0) {
                log.warn("微信登录失败，errcode={}", errcode);
                throw new LoginFailedException("微信登录失败，请重新获取登录凭证");
            }
            String openid = result.getString("openid");
            if (!StringUtils.hasText(openid)) {
                throw new LoginFailedException("微信登录未返回用户标识");
            }
            return openid;
        } catch (LoginFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            // URL/异常消息可能包含 AppSecret 或 code，只记录异常类型。
            log.error("调用微信登录接口失败，异常类型：{}", ex.getClass().getSimpleName());
            throw new LoginFailedException("微信登录服务暂不可用，请稍后重试");
        }
    }
}
