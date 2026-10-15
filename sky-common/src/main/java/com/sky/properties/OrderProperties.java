package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "sky.order")
@Data
public class OrderProperties {
    // 固定每单打包费（元），沿用订单模型中的整数类型。
    private Integer packAmount = 0;
    // 固定每单配送费（元）。
    private BigDecimal deliveryAmount = new BigDecimal("0.00");
}
