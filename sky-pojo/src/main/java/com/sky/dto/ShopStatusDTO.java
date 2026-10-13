package com.sky.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "设置店铺营业状态")
public class ShopStatusDTO implements Serializable {
    @Schema(description = "营业状态：0打烊，1营业", allowableValues = {"0", "1"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;
}
