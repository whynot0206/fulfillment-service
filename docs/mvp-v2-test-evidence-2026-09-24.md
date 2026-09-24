# V2 MVP 本机测试与修复记录（2026-09-24）

## 范围

本轮验收的是新增 Commerce、前端、Gateway 身份入口以及已有 Order、Inventory、Payment 的本机交易链路。使用 JDK 17、MySQL 8、Redis 和本机五个独立进程；不代表多实例或生产环境验收。

## 先复现的问题

1. Commerce 测试起初因 Mockito `anyCollection()` 无法匹配 `List<Long>` 参数而无法编译；改为 `anyList()` 后，33 个结算测试中 31 个失败，均指向 `revalidate`、`digest`、`claimIdempotencyKey` 未实现。
2. 成功下单清空购物车后，同键重放在读取幂等记录前即报 `CART_EMPTY`。在 `checkout_request` 保存服务端计算的 `total_amount`，购物车为空时按键查询并校验金额，再返回原订单。迁移脚本既支持首次建表，也支持已建表环境补列。
3. 订单服务直连端口接受伪造的 `X-User-Id`，未带鉴权读取测试订单返回 HTTP 200。现在 `/api/orders/**` 要求内部令牌；网关剥离客户端提交的内部令牌，在订单路由上重新注入。相同直连请求现为 HTTP 401，经网关携带有效 JWT 仍为 HTTP 200。
4. Windows MySQL 客户端第一次执行中文种子数据时出现 `Incorrect string value`；指定 `--default-character-set=utf8mb4` 后种子脚本成功。手册的种子命令已同步。

## 自动化结果

| 验证 | 结果 |
| --- | --- |
| 微服务 Reactor `mvn test` | 109 项通过，0 失败、0 错误、0 跳过 |
| 根目录单体 `run-tests.ps1` | 47 项通过，0 失败、0 错误、0 跳过；脚本可从本地 `.env` 读取 `MYSQL_ROOT_PASSWORD` |
| 前端 Vite 生产构建 | 38 个模块转换，构建成功 |
| `git diff --check` | 通过 |

测试覆盖重新校验、价格快照、库存未知、摘要规范化、幂等键冲突、成功后空购物车重放、订单服务结果未知和直连订单鉴权。

## 本机 HTTP 与数据结果

- Gateway、Order、Inventory、Commerce 四个健康端点均为 HTTP 200 / `UP`。
- 匿名商品列表返回 3 个 SPU；注册成功；匿名购物车请求为 HTTP 401。
- 两次真实结算均返回 `RESERVED`。同键再次提交返回相同 `orderId`，且 `replayed=true`；对应用户订单列表只有一条订单。
- 网关订单详情 HTTP 200；直连 Order 并伪造 `X-User-Id` 为 HTTP 401。
- 两笔订单经有签名的模拟支付回调返回 `ACCEPTED`，重复回调仍返回 `ACCEPTED`。数据库中两笔订单状态为已支付，两个库存锁状态为已确认，SKU 1001/1002 的 `lock_stock` 均为 0。
- 测试创建的三个本地账号及两笔已支付订单保留在演示库中；SKU 1001/1002 各成交 1 件。

## 仍需验证的边界

本轮验证了前端生产构建和后端 HTTP 链路，未完成浏览器交互逐页验收、多实例竞争、故障注入、真实支付网关或部署后的网络隔离。Commerce 的 `IN_PROGRESS` 幂等记录在下游结果未知时仍需人工核对订单列表；后续应增加基于已分配订单号的自动恢复。前端没有模拟支付页。
