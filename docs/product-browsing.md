# 用户端商品浏览接口

四个接口均为 GET，公开访问，不需要 authentication。结果沿用 `Result`：成功 code=1，data 为数组；没有结果时返回空数组。店铺打烊仍可浏览。

| 路径 | 参数 | 返回 data |
| --- | --- | --- |
| `/user/category/list` | type 可选，1菜品/2套餐，不传查所有启用分类 | `List<Category>` |
| `/user/dish/list` | categoryId 必填，正整数 | `List<DishVO>`，每道菜包含 flavors；无口味为 [] |
| `/user/setmeal/list` | categoryId 必填，正整数 | `List<Setmeal>` |
| `/user/setmeal/dish/{id}` | id 为套餐id，正整数 | `List<DishItemVO>`：name、copies、image、description |

直连后端使用 `http://localhost:8080`；经过现有 Nginx 使用 `http://localhost:90`，用户端路径不加 `/api`。

## 可见性和排序

- 分类只返回 status=1，按 sort 升序、create_time 降序排列。
- 菜品必须 status=1，且属于启用的菜品分类（type=1）。按创建时间、id 降序，口味按id升序。
- 套餐必须 status=1，且属于启用的套餐分类（type=2）。套餐关联菜品存在停售、异常状态或记录缺失时，隐藏整个套餐。套餐按创建时间、id降序。
- 套餐内菜品接口执行同一套可见性条件，不能通过已知套餐id绕过隐藏规则；不存在或不可见时返回 []。菜品使用当前名称、图片、描述，copies 来自套餐关系表，按关系id升序。
- 分类不存在、禁用或类型不匹配时，对应商品列表返回 []。
- 本次不加入分页、搜索、独立商品详情、购物车或 Redis 缓存。查询实时读取 MySQL。

## 参数和鉴权

非法 type、缺失/非正 categoryId、非正套餐id返回 code=0 和业务提示；无法转换为数字的参数返回“请求参数格式错误”。

仅放行以上具体路径。其他 `/user/**` 仍需用户 JWT；管理端保持原有查询和鉴权规则，能够查询停售或禁用数据。

## 示例

```http
GET /user/dish/list?categoryId=11
```

```json
{"code":1,"msg":null,"data":[{"id":46,"name":"示例菜品","categoryId":11,"price":25.00,"image":"https://example.com/dish.png","description":"示例描述","status":1,"updateTime":null,"categoryName":"示例分类","flavors":[{"id":1,"dishId":46,"name":"辣度","value":"[\"不辣\",\"微辣\"]"}]}]}
```

商品信息仅用于展示。未来提交订单时仍需重新校验价格、营业状态和商品可售性。

## 验证

常规验证：`mvn -o -pl sky-server -am test`。

本地 MySQL 查询验证（PowerShell 中需给 -D 参数加引号）：

```powershell
mvn -o -pl sky-server -am test '-Dbrowse.mysql.tests=true'
```

数据库测试读取本地 application-dev.yml，使用随机 browse_test_ 前缀的独立测试表，结束后清理。只替换测试 SQL 的表标识，查询条件和结果映射直接加载生产 Mapper XML，不修改业务表或数据。运行账号需要创建及删除测试表的权限。
