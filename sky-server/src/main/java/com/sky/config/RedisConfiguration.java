package com.sky.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * 创建 RedisTemplate 对象：统一规定「Java 对象 <-> Redis 字节」的转换方式
 */
@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        log.info("开始创建 Redis 模板对象...");

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        // 连接工厂由 Spring Boot 根据 application.yml 的 spring.data.redis.* 自动装配（默认 Lettuce 客户端）
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        // 1) key（含 hash 的 field）统一用字符串序列化器：redis-cli 里能直接读懂
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);

        // 2) value 用 Jackson 3 的 JSON 序列化器，并开启类型信息（带安全白名单）
        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.sky.")    // 项目里的实体 / DTO / VO
                        .allowIfSubType("java.util.")  // List、ArrayList 等集合
                        .allowIfSubType("java.math.")  // BigDecimal 等
                        .build())
                .build();
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        return redisTemplate;
    }
}
