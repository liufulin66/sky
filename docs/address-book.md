# 用户端地址簿接口

所有接口需要 `authentication: <用户JWT>`，用户id只从后端 BaseContext 获取。地址读取、修改、删除、设置默认均限制 `id + user_id`，不可操作他人地址。

直连后端使用 `http://localhost:8080`；现有 Nginx 使用 `http://localhost:90`，用户端路径不加 `/api`。

| 方法和路径 | 请求 | 返回 data |
| --- | --- | --- |
| `GET /user/addressBook/list` | 无 | AddressBook 数组，空列表为 [] |
| `POST /user/addressBook` | JSON 地址表单 | null |
| `GET /user/addressBook/{id}` | 路径中的地址id | AddressBook |
| `PUT /user/addressBook` | JSON 完整地址表单，包含id | null |
| `DELETE /user/addressBook?id=1` | 查询参数id | null |
| `PUT /user/addressBook/default` | JSON：`{"id":1}` | null |
| `GET /user/addressBook/default` | 无 | 默认 AddressBook，没有时为 null |

统一使用 Result：成功 code=1；参数错误、地址不存在或不属于当前用户时 code=0，msg为具体提示；缺失、无效或过期JWT返回HTTP401。

## 地址表单

```json
{
  "consignee": "张三",
  "phone": "13800138000",
  "sex": "1",
  "provinceCode": "110000",
  "provinceName": "北京市",
  "cityCode": "110100",
  "cityName": "北京市",
  "districtCode": "110101",
  "districtName": "东城区",
  "detail": "示例路1号101室",
  "label": "家"
}
```

- 修改时增加 `id`，使用完整表单保存；可选字段不传或传空白会清空对应字段。
- 收货人、手机号、详细地址必填，字符串去掉首尾空白。手机号为11位数字；性别可不传，传入时只支持字符串 `"0"`（女）或 `"1"`（男）。
- 长度沿用已有表：收货人50、区划编号12、区域名称32、详细地址200、标签100。省市区字段和标签可不传；不做外部行政区划查询。
- 新增忽略客户端id；`userId`、`isDefault` 不属于表单字段，客户端伪造它们不会改变身份或默认状态。

## 默认地址及删除规则

- 新增地址一律 `is_default=0`，包括第一条地址，不自动设置默认。
- 普通修改保留原默认状态；默认状态只能由设置默认接口调整。
- 设置默认前先验证地址归属，再在同一事务中取消当前用户的旧默认、设置目标默认。同一用户所有地址写操作先锁用户行，避免并发设置留下多个默认地址。
- 删除为物理删除。删除默认地址后不补选；没有默认地址时查询成功，`data=null`。删除不存在或他人地址统一提示“地址不存在或无权操作”。
- 地址列表默认地址优先，其余按id降序。
- 复用已有 MyBatis、事务和用户锁，不增加依赖，不改业务表结构，不引入 Redis 缓存。

## 验证

常规验证：`mvn -o -pl sky-server -am test`。

本地 MySQL 隔离表验证：`mvn -o -pl sky-server -am test '-Daddress.mysql.tests=true'`。读取本地 application-dev.yml，创建随机前缀测试表，加载生产 Mapper SQL，仅替换测试表名；结束后清理，不改业务表和已有地址。

小程序在新增、修改、删除、设置默认成功后，重新查询列表和默认地址更新页面；无默认地址时引导用户选择，不自动代选。
