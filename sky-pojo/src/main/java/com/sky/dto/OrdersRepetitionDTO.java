package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/** 再来一单的操作标识，独立于提交订单的requestId。 */
@Data
public class OrdersRepetitionDTO implements Serializable {
    private String requestId;
}
