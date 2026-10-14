# 微信小程序登录接口

## 接口约定

- 直连后端：`POST http://localhost:8080/user/user/login`
- 经过当前 Nginx：`POST http://localhost:90/user/user/login`（不加 `/api` 前缀）。
- 请求头：`Content-Type: application/json`。登录无需 JWT。
- 请求体：`{"code":"小程序 wx.login() 获取的临时登录凭证"}`。

成功响应：

```json
{"code":1,"msg":null,"data":{"id":5,"openid":"微信返回的用户标识","token":"后端签发的JWT"}}
```

业务失败沿用 `Result`：`code=0`，`msg` 为原因；鉴权失败返回 HTTP 401。

## 实现流程

1. 校验 code 非空，读取 `sky.wechat.appid/secret`。
2. 后端 GET `https://api.weixin.qq.com/sns/jscode2session`，携带 `appid`、`secret`、`js_code`、`grant_type=authorization_code`。
3. 检查微信业务错误码和 openid，不接受客户端提供的身份标识。
4. 按 openid 查询；首次登录插入 openid、create_time，回填自增主键。并发重复插入后重新查询已有用户。
5. 使用 `sky.jwt.user-secret-key` 和 `user-ttl` 签发 JWT，载荷含 `userId`。

openid 唯一约束是并发注册正确工作的前提。已有数据库使用 `sql/migrations/2026-10-14-user-openid-unique.sql`，先核对重复和已有索引；不要重导包含建表和样例数据的完整 SQL。

## 用户端鉴权

后续业务请求携带 `authentication: <token>`，名称来自 `sky.jwt.user-token-name`；当前有效期两小时。

`/user/**` 注册用户 JWT 拦截器，放行登录、店铺查询及已确认的商品浏览路径：`/user/user/login`、`/user/shop/status`、`/user/category/list`、`/user/dish/list`、`/user/setmeal/list`、`/user/setmeal/dish/*`。商品浏览约定见 [商品浏览接口文档](product-browsing.md)。其他用户端接口继续鉴权。校验成功后将 userId 写入 BaseContext，请求结束清理 ThreadLocal。管理员 token 不能替代用户 token。

## 配置与联调

现有 `application.yml` 从本地配置读取微信 AppID、AppSecret，不需新增依赖。它们必须与生成 code 的小程序对应；配置项存在不代表凭证已通过微信验证。

使用微信开发者工具或小程序调用 wx.login() 获取新的 code，再调用本接口。Apifox 不能凭空生成有效微信 code，不应重复使用已消费的 code。AppSecret、code、session_key、JWT 不打印到日志，session_key 不返回客户端。

验证首次注册、再次登录不重复注册、空或无效 code、微信异常响应、并发注册、JWT 缺失/过期/签名错误、公开店铺查询和 ThreadLocal 清理。单元测试模拟微信响应，不能代替真实小程序联调。

本次不包含手机号、昵称、头像授权或支付功能。

## 文档依据

- 微信 code2Session：https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html
- 腾讯 CloudBase 登录接入说明：https://docs.cloudbase.net/recipes/add-auth-wechat-miniprogram

开发时微信文档正文无法抓取，流程和请求参数通过腾讯 CloudBase 官方文档搜索摘要核对。接口路径和返回字段按本项目确认的约定实现。
