package com.sky.config;

import com.sky.properties.AliOssProperties;
import com.sky.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册阿里云 OSS 工具类的 Bean
 */
@Configuration
@Slf4j
public class OssConfiguration {

    @Bean
    @ConditionalOnMissingBean  // 容器里没有同类型 Bean 时才创建，防止重复注册
    public AliOssUtil aliOssUtil(AliOssProperties aliOssProperties) {
        log.info("开始创建阿里云OSS工具类对象：{}", aliOssProperties);
        return new AliOssUtil(aliOssProperties.getEndpoint(),
        aliOssProperties.getRegion(),
        aliOssProperties.getAccessKeyId(),
        aliOssProperties.getAccessKeySecret(),
        aliOssProperties.getBucketName());

    }
}
