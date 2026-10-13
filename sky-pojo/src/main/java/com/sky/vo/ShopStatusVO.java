package com.sky.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "店铺营业状态")
public class ShopStatusVO implements Serializable {
    @Schema(description = "营业状态：0打烊，1营业")
    private Integer status;
}
