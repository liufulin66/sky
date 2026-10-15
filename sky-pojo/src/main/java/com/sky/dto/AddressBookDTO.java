package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/** 地址表单。用户身份和默认标记由服务端管理，不允许通过表单修改。 */
@Data
public class AddressBookDTO implements Serializable {
    private Long id;
    private String consignee;
    private String phone;
    private String sex;
    private String provinceCode;
    private String provinceName;
    private String cityCode;
    private String cityName;
    private String districtCode;
    private String districtName;
    private String detail;
    private String label;
}
