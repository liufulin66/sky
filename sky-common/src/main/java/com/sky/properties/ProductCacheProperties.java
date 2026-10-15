package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "sky.cache.product")
@Data
public class ProductCacheProperties {
    private boolean enabled = true;
    private Duration ttl = Duration.ofMinutes(5);
    private int jitterSeconds = 60;
    private Duration emptyTtl = Duration.ofSeconds(30);
}
