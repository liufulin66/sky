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
- 四个浏览接口使用 Redis 缓存；分页、搜索和独立商品详情尚未加入。购物车添加仍直接查询 MySQL 校验商品可售性和当前价格。

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

## Redis 缓存

采用旁路缓存：先读 Redis，命中就返回；未命中时按原有 Mapper SQL 查询 MySQL，再写入 Redis。缓存的数据是原有实体或 VO 列表，不改变前端路径、返回字段、鉴权和可见性规则。

- 正常列表缓存 5 分钟，加 0～60 秒随机偏移。随机偏移可以分散多个分类同时过期的时间。
- 空列表同样是有效缓存，保存 30 秒，避免不存在或不可见的分类、套餐被反复查库。
- Redis 连接、读取、写入或反序列化失败时记录 WARN 日志，浏览回退到 MySQL；MySQL 自身的错误正常向上传递，不当成缓存故障重复查询。Redis 连接和命令超时均配置为 1 秒。
- 热点键未命中时，使用 Redis SET NX 加 10 秒有效期的重建锁。持有者拿锁后再次检查缓存，避免重复重建。其他请求最多等待约 200 毫秒，期间检查是否已有结果；仍未命中便直接查库，不回填缓存。慢查询或 Redis 故障期间仍可能产生多次数据库查询，互斥锁不能替代数据库容量保障。
- 锁使用每次请求独有的令牌，释放时通过 Lua 原子比对令牌后删除，防止锁过期后误删其他请求的新锁。

缓存键的命名空间为 `sky:product:v1:`，与店铺营业状态键相互独立：

| 内容 | 键 |
| --- | --- |
| 当前目录版本（无 TTL） | `sky:product:v1:version` |
| 分类列表 | `sky:product:v1:<版本>:category:type:<all/1/2>` |
| 菜品及口味 | `sky:product:v1:<版本>:dish:category:<categoryId>` |
| 套餐列表 | `sky:product:v1:<版本>:setmeal:category:<categoryId>` |
| 套餐内菜品 | `sky:product:v1:<版本>:setmeal:dish:<id>` |

后台分类、菜品、套餐的新增、编辑、删除和状态修改，在数据库事务提交后将版本更新为新的 UUID；回滚不更新版本。版本切换使上述所有缓存对后续请求失效，包含菜品修改对关联套餐的影响，以及商品转移分类的影响。重建期间版本改变，则旧查询不回填；旧版本的数据键保留到 TTL 到期自动回收，生产代码不使用 KEYS 或通配删除。

统一失效的代价是修改一件商品也会让其他分类重新加载，适合当前目录规模和后台修改频率。经常大批量修改或目录规模增大时，再按分类与关联套餐细化失效范围。

缓存属于展示数据，存在正常的并发读取窗口。如果数据库修改已提交，但 Redis 失效失败，记录日志并保持修改成功，旧展示结果最长由剩余 TTL（正常结果不超过 6 分钟）兜底；直接改数据库也不会触发主动失效。购物车添加的可售性和价格检查不读取浏览缓存。用户数量、购物车和订单不在本次缓存范围内。

配置位于 `application.yml`：

```yaml
sky:
  cache:
    product:
      enabled: true
      ttl: 5m
      jitter-seconds: 60
      empty-ttl: 30s
```

将 enabled 设为 false 并重启服务，可让四个接口直接查询数据库。DEBUG 日志可查看缓存重建数量与有效期；INFO 日志记录目录版本更新。

## 验证

常规验证：`mvn -o -pl sky-server -am test`。

本地 MySQL 查询验证（PowerShell 中需给 -D 参数加引号）：

```powershell
mvn -o -pl sky-server -am test '-Dbrowse.mysql.tests=true'
```

数据库测试读取本地 application-dev.yml，使用随机 browse_test_ 前缀的独立测试表，结束后清理。只替换测试 SQL 的表标识，查询条件和结果映射直接加载生产 Mapper XML，不修改业务表或数据。运行账号需要创建及删除测试表的权限。

完整本地验证（包含真实 Redis 缓存和既有购物车回归）：

```powershell
mvn -o -pl sky-server -am test '-Dbrowse.redis.tests=true' '-Dbrowse.mysql.tests=true' '-Dcart.mysql.tests=true'
```

Redis 测试读取相同本地 Redis 配置，使用随机 `product_cache_test_` 命名空间并清理，仅操作测试键。验证四种返回类型（含金额、时间和口味）的序列化、实际缓存命中、正常与空结果 TTL、版本失效、并发重建及锁令牌保护。单元测试另外覆盖提交/回滚、Redis 故障回退、数据库异常、重建等待超时和全部后台修改入口。

手工联调时，同一分类连续请求两次，第一次未命中会产生浏览 Mapper SQL，第二次命中不再查询这些 SQL；后台修改或停售后再请求应重新查库。前端重启后端即可沿用现有接口，无需改请求参数。
