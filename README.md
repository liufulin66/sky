# 苍穹外卖后端 · Sky Take Out

基于 Spring Boot、MyBatis、MySQL 和 Redis 的单店外卖后端项目，包含商家管理和微信小程序用户端接口。覆盖商品浏览、购物车、地址簿、下单与订单管理、工作台、数据统计以及 Excel 运营报表导出。

项目以学习和业务实践为目的，重点包括数据库事务、订单状态流转、幂等处理、Redis 旁路缓存和 SQL 聚合统计。当前仓库提供后端源码；管理端页面和微信小程序需另行准备。

## 功能概览

| 模块 | 已实现内容 |
| --- | --- |
| 员工管理 | 员工登录、分页查询、新增与修改、启用禁用、密码修改 |
| 商品管理 | 分类、菜品与口味、套餐及关联菜品管理、起售停售 |
| 文件上传 | 阿里云 OSS 上传接口，需配置有效 OSS 凭证 |
| 店铺管理 | 设置与查询营业状态，状态保存在 Redis |
| 用户登录 | 微信 code2Session 登录、首次注册、用户 JWT |
| 商品浏览 | 分类、菜品与口味、套餐、套餐内菜品查询及 Redis 缓存 |
| 购物车 | 添加、查询、减少、清空；按当前用户隔离数据 |
| 地址簿 | 新增、列表、详情、修改、删除、设置及查询默认地址 |
| 用户订单 | 提交订单、历史分页、详情、取消未付款订单、再来一单 |
| 商家订单 | 搜索、详情、数量统计、接单、派送、完成、未付款取消及拒单校验 |
| 工作台 | 今日营业数据、今日订单概览、菜品与套餐起售停售数量 |
| 数据统计 | 日期区间营业额、用户增长、订单统计、商品销量 Top10 |
| Excel 报表 | 经营汇总、每日明细、销量 Top10 三个工作表，支持默认或自定义日期范围 |

### 当前业务边界

- **真实支付、支付回调和退款尚未接入。**正常提交的订单保持待付款；已支付订单的拒单、取消返回明确提示，不将退款标记为成功。
- 接单、派送、完成要求订单已支付且原状态符合条件。自动化测试使用测试表中的已支付订单验证流程，没有提供模拟支付业务接口。
- 未实现库存扣减、未付款订单超时自动取消、催单通知及多商户隔离。
- 统计以订单当前状态为准，营业额和有效订单要求“已完成且已支付”，按下单日期归属；订单后续完成或退款时，历史统计可能变化。

## 技术栈

| 技术 | 本项目用途 |
| --- | --- |
| Java 17 | 后端运行与开发，使用 Java record 等语法 |
| Spring Boot 4.1.1 | Web 应用与组件管理 |
| MyBatis、PageHelper | SQL 映射和分页查询 |
| MySQL 8 | 业务数据、事务、唯一约束、行锁及窗口函数统计 |
| Spring Data Redis | 商品浏览缓存、热点重建锁和店铺营业状态 |
| JWT / JJWT | 管理员与用户身份认证 |
| Spring AOP | 公共审计字段填充 |
| springdoc-openapi | OpenAPI 与 Swagger UI |
| Apache POI 5.5.1 | `.xlsx` 报表生成 |
| 阿里云 OSS SDK V2 | 商品图片等文件上传 |
| JUnit 5、Mockito、MockMvc | 业务、接口及数据库集成测试 |

依赖版本以根目录和各模块的 `pom.xml` 为准。微信支付工具类及相关依赖的存在不代表支付业务已经接入。

## 项目结构

```text
sky/
├── sky-common/                 # 结果封装、异常、常量、配置属性、公共工具
├── sky-pojo/                   # Entity、DTO、VO
├── sky-server/
│   ├── src/main/java/com/sky/
│   │   ├── controller/admin/   # 管理端接口
│   │   ├── controller/user/    # 小程序用户端接口
│   │   ├── service/            # 业务接口与实现
│   │   ├── mapper/             # MyBatis Mapper
│   │   ├── cache/              # 商品浏览缓存
│   │   ├── interceptor/        # JWT 校验
│   │   ├── config/             # Web、Redis、OSS 配置
│   │   └── utils/              # Excel 报表渲染
│   ├── src/main/resources/     # application 配置与 Mapper XML
│   └── src/test/               # 已纳入版本管理的回归测试
├── docs/                       # 业务规则、接口及实现说明
├── sql/migrations/             # 需手动审阅执行的数据库迁移
├── sky-take-out.sql             # 基线结构和示例数据，含 DROP TABLE
└── pom.xml                     # Maven 多模块聚合与依赖管理
```

## 本地运行

### 1. 准备环境

- JDK 17、Maven 3.9.x。
- MySQL 8：迁移使用 `utf8mb4_0900_ai_ci`，销量统计使用窗口函数。
- 可连接的 Redis 实例。
- 如需联调微信登录或图片上传，另行准备小程序 AppID / AppSecret 和 OSS 配置。

```shell
git clone https://github.com/liufulin66/sky.git
cd sky
```

以下命令均从仓库根目录执行。

### 2. 初始化数据库

先创建专用开发数据库，例如：

```sql
CREATE DATABASE sky_take_out
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

仅向**新建空库**导入 [sky-take-out.sql](sky-take-out.sql)。该文件包含 `DROP TABLE` 和示例数据，不要用它覆盖已有业务数据库。

基线导入后，按下面的说明检查和执行迁移；应用不会自动执行这些 SQL：

| 文件 | 执行说明 |
| --- | --- |
| [2026-10-14-user-openid-unique.sql](sql/migrations/2026-10-14-user-openid-unique.sql) | 用于旧库补充用户 openid 唯一约束；当前基线已包含 `idx_user_openid`，已有索引时跳过新增索引语句 |
| [2026-10-15-user-orders.sql](sql/migrations/2026-10-15-user-orders.sql) | 下单幂等字段、订单自增 ID、费用及地址快照、订单号和用户请求唯一约束、查询索引 |
| [2026-10-16-order-repetition.sql](sql/migrations/2026-10-16-order-repetition.sql) | 在订单迁移之后新增“再来一单”成功操作记录表 |

已有数据库先备份、核对字段及索引，并处理迁移脚本提示的重复数据；已经执行成功的迁移不要重复执行。工作台、数据统计和 Excel 导出复用现有表结构，无额外迁移。

### 3. 配置本地开发文件

默认启用 `dev` profile。在 `sky-server/src/main/resources/` 下自行创建 `application-dev.yml`，例如：

```yaml
sky:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    host: 127.0.0.1
    port: 3306
    database: sky_take_out
    username: root
    password: "your-local-database-password"

  redis:
    host: 127.0.0.1
    port: 6379
    database: 0

  jwt:
    admin-secret-key: "replace-with-a-random-admin-secret-at-least-32-bytes"
    user-secret-key: "replace-with-a-different-random-user-secret-at-least-32-bytes"

  wechat:
    appid: ""
    secret: ""

  alioss:
    endpoint: https://oss-cn-hangzhou.aliyuncs.com
    region: cn-hangzhou
    access-key-id: ""
    access-key-secret: ""
    bucket-name: ""
```

将数据库密码和 JWT 示例值替换为自己的本地配置；两个 JWT 密钥使用不同的随机值。`application-dev.yml` 已被 `.gitignore` 忽略，不提交真实密码、AppSecret、OSS 凭证或证书。

微信和 OSS 字段可先留空以准备其他模块；真实微信登录和文件上传必须填入有效配置。当前不需要配置支付商户号或支付证书。若 Redis 设置了密码，在本地配置中额外设置 `spring.data.redis.password`。

### 4. 构建与启动

```shell
mvn -pl sky-server -am clean package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

也可在 IDE 中运行 `com.sky.SkyApplication`。默认端口为 `8080`：

| 地址 | 用途 |
| --- | --- |
| `http://localhost:8080/swagger-ui/index.html` | Swagger UI，查看请求参数和返回对象 |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON |
| `http://localhost:8080/user/shop/status` | 公开查询店铺营业状态 |

管理端静态页面、小程序及 Nginx 反向代理需单独配置；本文接口示例均为直接访问后端的路径。

### 5. 联调顺序

1. 使用数据库中已配置的员工账号调用 `POST /admin/employee/login`，取得管理员 JWT。
2. 调用 `PUT /admin/shop/1` 开店，管理请求携带 `token`。未设置状态时默认打烊；营业状态不可确认时拒绝新增订单。
3. 配置分类、菜品、套餐，调用公开商品浏览接口检查可见性。
4. 小程序通过 `wx.login()` 获得临时 code，再调用 `POST /user/user/login`，取得用户 JWT。
5. 用户请求携带 `authentication`，维护地址和购物车、提交订单、查询或取消订单。
6. 管理员可访问工作台、统计报表和 Excel 导出；已支付订单的处理流程参见订单管理文档。

Redis 中的营业状态是状态存储，不是 MySQL 的副本。商品浏览缓存异常时可以回退数据库，但不能据此绕过店铺营业状态或下单校验。

## 接口与鉴权

| 接口范围 | 请求头 | 说明 |
| --- | --- | --- |
| `/admin/**` | `token: <管理员JWT>` | 员工登录接口放行，其余管理端接口需要管理员身份 |
| `/user/**` 业务接口 | `authentication: <用户JWT>` | 购物车、地址簿、下单及本人订单操作 |
| 用户登录、店铺状态及指定商品浏览路径 | 无需 JWT | 具体放行路径见业务文档 |

普通 JSON 接口统一返回：

```json
{"code":1,"msg":null,"data":{}}
```

业务失败通常仍为 HTTP 200，返回 `code=0` 和错误原因；JWT 缺失或无效返回 HTTP 401。分页数据使用 `total`、`records`。Excel 导出成功时返回文件字节，不使用 JSON 包装。

## 关键业务实现

- **商品浏览缓存**：旁路缓存、空结果短 TTL、随机过期偏移、Redis 互斥重建锁；后台商品修改提交后切换缓存版本。展示数据允许短暂延迟，下单与加购重新读取数据库校验商品及价格。
- **购物车与地址簿**：用户 ID 来自 JWT；写操作使用事务和用户行锁协调，防止同一用户结算与购物车修改相互覆盖。
- **提交订单**：重新计算金额，保存商品、地址、费用快照；订单主表、明细和购物车清理在同一事务中完成；相同用户请求 ID 和相同表单重试返回原订单。
- **再来一单**：读取历史商品后按当前价格和可售性追加购物车；任一商品失败整体回滚；独立持久化请求 ID 防止重复追加。
- **订单状态操作**：行锁配合带原状态、支付状态的条件更新；重复操作不覆盖首次取消原因或送达时间。
- **统计报表**：数据库按日聚合，Java 补齐无数据日期；累计用户包含历史基数，完成率按区间总数计算，Top10 按商品类型与 ID 合并销售份数。
- **Excel 导出**：现有 Apache POI 生成三张工作表；结构化数据直接写单元格；三次统计查询在只读 REPEATABLE_READ 事务中读取同一快照。

### Excel 下载示例

```http
GET /admin/report/export
token: <管理员JWT>
```

无参数时默认导出北京时间最近 **30 个完整自然日，不含今天**。自定义范围时同时传入 `begin`、`end`，包含起止两天，最多 366 天：

```http
GET /admin/report/export?begin=2026-10-01&end=2026-10-03
token: <管理员JWT>
```

工作簿包含“经营汇总、每日明细、销量 Top10”。金额显示两位小数，比率显示为百分比，商品 ID 按文本保留。前端以 Blob 接收文件，并需识别失败时返回的 JSON 错误信息。

## 测试

普通测试不要求真实微信或 OSS 凭证；MySQL 集成测试通过系统属性显式启用：

```shell
mvn -pl sky-server -am test
```

在已准备本地 `application-dev.yml` 和专用开发/测试数据库后，可运行数据库回归：

```shell
mvn -pl sky-server -am test "-Daddress.mysql.tests=true" "-Dorder.mysql.tests=true" "-Dworkspace.mysql.tests=true" "-Dreport.mysql.tests=true"
```

这些数据库测试创建和清理随机前缀测试表，不运行业务库迁移；测试账号需具备相应建表、改表、查询和清理权限。仅在开发或测试库启用。

已纳入版本管理的测试覆盖身份隔离、请求绑定、事务回滚、幂等重试、并发状态操作、统计日期边界、空数据、Top10 合并与快照名称，以及 Excel 文件重新打开、单元格格式、下载响应和一致性快照。测试不会代替真实微信登录或 OSS 联调。

## 详细业务文档

| 文档 | 内容 |
| --- | --- |
| [微信登录](docs/wechat-login.md) | code2Session、用户注册和 JWT |
| [商品浏览与 Redis 缓存](docs/product-browsing.md) | 可见性规则、缓存命名、失效和故障回退 |
| [购物车](docs/shopping-cart.md) | 商品变体、数量、价格刷新及用户隔离 |
| [地址簿](docs/address-book.md) | 七个接口及默认地址规则 |
| [用户订单](docs/user-orders.md) | 提交、查询、取消、再来一单和迁移说明 |
| [商家订单管理](docs/admin-orders.md) | 八个接口、状态流转和支付退款边界 |
| [工作台](docs/workspace.md) | 今日统计及商品概览口径 |
| [数据统计与 Excel 导出](docs/reports.md) | 四个图表接口、日期补齐、Top10 和工作簿生成 |

## 后续可扩展方向

- 真实支付、关单、回调验签、退款及失败补偿。
- 未付款订单超时自动取消、催单和商家消息通知。
- 如有业务需要，进一步设计库存、多店铺和更细粒度的缓存失效。

以上为待实现方向，不代表当前已经提供对应业务接口。
