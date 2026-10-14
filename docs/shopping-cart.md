# 用户端购物车接口

所有接口需要 `authentication: <用户JWT>`，用户id只取后端 BaseContext；客户端不能指定用户、商品价格或购物车条目id来操作其他用户数据。

直连后端使用 `http://localhost:8080`；现有 Nginx 使用 `http://localhost:90`，用户端路径不加 `/api`。

| 方法和路径 | 请求 | 返回 |
| --- | --- | --- |
| `POST /user/shoppingCart/add` | JSON：dishId或setmealId二选一，菜品可带dishFlavor | Result，成功code=1 |
| `GET /user/shoppingCart/list` | 无请求体 | Result，data为ShoppingCart数组，空车为[] |
| `POST /user/shoppingCart/sub` | 与add相同，定位同一商品及口味 | Result，成功code=1 |
| `DELETE /user/shoppingCart/clean` | 无请求体 | Result，成功code=1 |

## 示例

```json
{"dishId":46,"dishFlavor":"微辣,不要蒜"}
```

```json
{"setmealId":32}
```

查询成功示例（字段值仅示意）：

```json
{"code":1,"msg":null,"data":[{"id":1,"name":"示例菜品","userId":5,"dishId":46,"setmealId":null,"dishFlavor":"微辣,不要蒜","number":2,"amount":25.00,"image":"https://example.com/dish.png","createTime":"2026-10-14 12:00"}]}
```

`amount` 是单价，`number` 是数量。前端总数量为各条目 number 之和，总价为各条目 amount×number 之和；不能把amount直接当条目小计。

## 业务规则

- 同一用户、相同商品、相同口味合并，每次添加+1、减少-1，数量为0删除；减少不存在的条目也成功。不同口味分开。
- 口味是字符串，去掉首尾空白；null、空串、纯空白统一为空串。内部内容不重新排序或解析，添加和减少需传一致字符串；最长50字符。套餐不能指定单独菜品口味。
- 添加校验与商品浏览一致：启用分类下的在售商品；套餐内有停售、异常或缺失菜品不能添加。
- 价格、名称、图片取数据库。首次添加记录单价；查询保留已有条目及单价，停售或改价不自动删除或刷新。再次添加时重新检查可售性，更新该条目的名称、图片、单价并+1。
- 减少和清空不检查商品是否仍在售，即使商品删除仍可清理已有条目。
- 打烊允许操作购物车；未来提交订单仍须校验营业状态、最新价格及可售性。
- 写操作使用事务，先 SELECT FOR UPDATE 锁定当前用户行，再查询/变更购物车。同一用户并发首次添加不会重复插入，增减/清空按事务顺序执行。不新增依赖或修改业务表结构。
- 商品id须为正整数；非法参数、不可售商品返回code=0和具体msg。缺失、无效或过期JWT返回HTTP401。

## 小程序联调

1. 先微信登录，保存返回的用户token。
2. 添加/减少/清空成功（code=1）后重新调用list，用结果刷新底部购物车数量和金额。
3. 验证同口味合并、不同口味分开、减到零、清空、换用户隔离，以及失效token。

仅添加接口返回成功不会自动改变小程序本地界面，前端必须刷新列表或同步本地状态。本次不实现订单或支付。

## 验证

常规测试：`mvn -o -pl sky-server -am test`。

本地MySQL隔离表验证：`mvn -o -pl sky-server -am test '-Dcart.mysql.tests=true' '-Dbrowse.mysql.tests=true'`。数据库测试使用随机前缀的测试表，并清理自身创建的表，不修改业务数据。

事务中的用户行锁随提交或回滚释放，原理见 [MySQL 锁定读说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)。
