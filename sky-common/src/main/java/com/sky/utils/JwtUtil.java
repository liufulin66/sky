package com.sky.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类（基于 jjwt 0.12+）
 *
 * 注意：jjwt 0.12 起 HS256 密钥强制要求不少于 256 位（即 32 个字符），
 * 密钥在 application.yml 的 sky.jwt.admin-secret-key / user-secret-key 中配置。
 */
public class JwtUtil {

    /**
     * 生成jwt
     * 使用Hs256算法, 私匙使用固定秘钥
     *
     * @param secretKey jwt秘钥
     * @param ttlMillis jwt过期时间(毫秒)
     * @param claims    设置的信息
     * @return
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        // 生成JWT的时间
        long expMillis = System.currentTimeMillis() + ttlMillis;
        Date exp = new Date(expMillis);

        return Jwts.builder()
                // 设置jwt的body（私有声明）
                .claims(claims)
                // 设置过期时间
                .expiration(exp)
                // 设置签名使用的签名算法和密钥
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Token解密
     *
     * @param secretKey jwt秘钥 此秘钥一定要保留好在服务端, 不能暴露出去, 否则sign就可以被伪造
     * @param token     加密后的token
     * @return
     */
    public static Claims parseJWT(String secretKey, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        return Jwts.parser()
                // 设置签名的秘钥
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
