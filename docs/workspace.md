# 工作台四个接口

日期区间的营业额、用户、订单和销量Top10统计见 [数据统计接口](reports.md)。

全部接口直连后端时以 `http://localhost:8080/admin/workspace` 为前缀，使用 `token: <管理员JWT>`。沿用管理端拦截器，用户JWT不能访问。当前为单店模型，查询整个店铺的数据，不使用管理员员工id作为用户过滤条件。

## 接口

| 方法和路径 | 返回对象及字段 |
| --- | --- |
| `GET /admin/workspace/businessData` | BusinessDataVO：turnover、validOrderCount、orderCompletionRate、unitPrice、newUsers |
| `GET /admin/workspace/overviewOrders` | OrderOverViewVO：waitingOrders、deliveredOrders、completedOrders、cancelledOrders、allOrders |
| `GET /admin/workspace/overviewDishes` | DishOverViewVO：sold、discontinued |
| `GET /admin/workspace/overviewSetmeals` | SetmealOverViewVO：sold、discontinued |

四个接口均不需要请求参数。客户端不能通过传入日期、用户id等参数改变统计范围。成功为code=1，数据放在data中；JWT缺失或无效返回HTTP401。

## 今日营业数据

“今天”按北京时间Asia/Shanghai计算。一次请求只取一次当天日期，再生成 `[今天00:00:00, 明天00:00:00)` 的范围。SQL使用大于等于起点、小于终点，确保今天零点包含在内、明天零点不包含在内。

| 字段 | 统计口径 |
| --- | --- |
| turnover | 今天下单、status=5已完成且pay_status=1已支付的订单金额amount之和 |
| validOrderCount | 上述有效订单数量，零元订单同样有效 |
| orderCompletionRate | 有效订单数÷今天全部订单数；分母包括未付款、已取消等订单 |
| unitPrice | 营业额÷有效订单数 |
| newUsers | user.create_time在今天范围内的用户数量，即首次注册数，不是今日登录人数 |

昨天提交、今天完成的订单不计入今天营业数据；今天提交但尚未完成的订单只进入总订单数，不进入营业额和有效订单数。已完成但未支付、已退款的异常历史订单不计入有效订单。没有订单或有效订单时，相应比率、客单价返回0，不出现除零、NaN或null。

所有订单聚合及新增用户计数在一条SQL中完成。用户数使用独立子查询，避免订单为空导致新增用户也丢失，也避免把订单和用户直接连接后重复计数。

营业额基于数据库DECIMAL聚合，以BigDecimal传到Service；平均客单价用BigDecimal除法并按HALF_UP四舍五入保留两位小数。完成率同样按HALF_UP保留四位小数，返回0～1的小数，例如0.4286，前端负责乘100显示百分比。最终适配已有BusinessDataVO的Double字段，保留原JSON数字类型；该对象用于展示，不能作为支付、结算金额的来源。

示例：

```json
{
  "code": 1,
  "msg": null,
  "data": {
    "turnover": 30.05,
    "validOrderCount": 3,
    "orderCompletionRate": 0.4286,
    "unitPrice": 10.02,
    "newUsers": 1
  }
}
```

## 今日订单概览

同样以今天的orders.order_time为范围：

| 字段 | 对应订单状态 |
| --- | --- |
| waitingOrders | 2待接单 |
| deliveredOrders | 3已接单，即待派送；字段名不表示已经送达 |
| completedOrders | 5已完成 |
| cancelledOrders | 6已取消 |
| allOrders | 今天下单的全部订单，包括待付款和派送中 |

全部数量不一定等于其他四项之和，因为返回对象没有单独列出状态1和4。这里按status计数，不额外按支付状态筛选；营业数据中的validOrderCount则需要已完成且已支付，遇到异常历史数据时两者可能不同。

工作台概览与 `/admin/order/statistics` 的范围不同：本接口只统计今天下单的订单；订单管理的数量接口继续覆盖所有日期，以便处理跨天积压。

## 菜品、套餐概览

分别在dish和setmeal表按自身status做条件聚合：status=1计入sold，status=0计入discontinued。

sold表示“已起售的商品数量”，不是卖出的份数。商品概览不限制创建日期，也不按用户端可售规则关联分类、套餐菜品或店铺营业状态。分类停用、套餐内菜品停售等情况可能导致小程序无法购买，但不会自动改变后台商品本身的status；本接口展示商家配置状态。

没有记录时两个字段均返回0。

## 代码与验证

调用链为WorkspaceController → WorkspaceServiceImpl → WorkspaceMapper.xml。每个接口执行一条统计SQL，使用CASE WHEN条件聚合和COALESCE空值归零，不把整表加载到Java里循环统计。不增加Redis缓存，刷新时重新查询数据库；四个HTTP请求分别读取，不能保证整张工作台页面处于同一时刻的数据快照。

Service使用JDK的Clock读取时间，生产使用北京时间；测试提供固定时钟，可稳定验证午夜切换，不受运行测试的日期影响。Clock只提供时间来源，不是定时任务或缓存组件。没有新增依赖、数据库字段或迁移SQL。

验证覆盖四个接口的管理员鉴权和字段契约、北京时间午夜切换、订单及用户的起止边界、有效订单与状态计数的区别、空表、无订单但有新用户、十进制金额和舍入、累计营业额超过单笔金额上限、商品自身状态统计。

MySQL集成测试使用真实基线建表结构和生产Mapper，仅创建、清理随机前缀测试表，不读写业务表。运行时启用 `-Dworkspace.mysql.tests=true`。
