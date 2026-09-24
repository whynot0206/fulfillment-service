# 周期 7：微服务运行切片

这个目录是从现有单体逐步拆分出的独立运行路径。根目录单体继续作为周期 0–6 的可复现实验基线，
这里用于验证真实网络边界、服务故障和补偿流程。

当前能力矩阵和对外口径见 [`../docs/project-function-boundary.md`](../docs/project-function-boundary.md)。周期 7–12 是本机四进程运行切片；V2 MVP 增加 Commerce，成为本机五进程链路。两者均不代表生产部署标准。

周期 11 后，Order 和 Inventory 使用同一 MySQL 实例中的独立 schema 与应用账号。根目录单体仍连接旧
`fulfillment` 库作为回归基线；它不再与微服务切片竞争同一批 Order Outbox 数据。

## 服务与端口

| 模块 | 默认端口 | 职责 |
|---|---:|---|
| `gateway` | 18080 | 统一入口与路由 |
| `order-service` | 18081 | 订单创建、预占状态、支付状态 |
| `inventory-service` | 18082 | 库存预占、释放、确认和查询 |
| `payment-service` | 18083 | 支付成功回调和仅供本地演示的模拟支付 |
| `commerce-service` | 18084 | V2 用户、商品、购物车和结算入口 |
| `fulfillment-api` | - | DTO 与 Feign 契约，不包含数据库实体 |

## 调用链

```text
客户端 -> Gateway -> Order Service -> Inventory Service
客户端 -> Gateway -> Payment Service -> Order Service
```

创建订单时，订单数据库事务与库存数据库事务已经分开。订单服务先保存“预占中”订单，再调用库存服务：

1. 库存明确成功：订单改为“已预占”。
2. 库存明确拒绝：订单改为“预占失败”。
3. 调用超时或连接中断：结果未知，订单服务调用幂等释放接口；释放失败时记录为“待补偿”。

库存接口使用 `orderId + skuId` 唯一键保证重复预占不会重复扣减，释放和确认通过状态条件更新保证幂等。
这个切片采用 Saga 式状态与补偿，不把 `@Transactional` 描述成跨服务事务。

后台补偿调度器持续重试“待补偿”订单。支付只允许更新库存已经预占成功的待支付订单；支付状态与
`PAYMENT_CONFIRMED` 本地消息在订单服务的同一事务提交。Outbox 发布器随后调用库存确认接口，按指数
退避重试，连续失败 10 次后进入死信。订单本地事件不会因一次进程故障直接丢失；死信和长期故障仍需
人工排查与恢复。

## 本地构建

```powershell
$env:ORDER_DB_PASSWORD = '<Order 应用账号密码>'
$env:INVENTORY_DB_PASSWORD = '<Inventory 应用账号密码>'
$env:INTERNAL_SERVICE_TOKEN = '<订单、库存和支付服务共用的内部调用令牌>'
$env:PAYMENT_CALLBACK_SECRET = '<支付回调 HMAC 密钥>'
& 'D:\vibecoding\.toolchains\maven\apache-maven-3.9.16\bin\mvn.cmd' `
  '-Dmaven.repo.local=D:\vibecoding\.m2\repository' `
  -f .\microservices\pom.xml test
```

首次运行前依次执行 `sql/migration-cycle7.sql` 至 `sql/migration-cycle11.sql`，随后用管理账号执行
`sql/migration-cycle11-split-schema.sql`。参考 `sql/provision-cycle11-users.sql.example` 创建应用账号，真实密码只通过环境变量提供：

```text
ORDER_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/fulfillment_order
ORDER_DB_USERNAME=fulfillment_order_app
ORDER_DB_PASSWORD=...
INVENTORY_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/fulfillment_inventory
INVENTORY_DB_USERNAME=fulfillment_inventory_app
INVENTORY_DB_PASSWORD=...
```

拆分脚本是停机复制脚本：使用 `INSERT IGNORE` 支持重跑，但不会覆盖目标 schema 已推进的数据。各服务通过环境变量配置依赖地址，默认指向上表中的本机端口。
当前阶段先用明确的服务 URL 验证网络和补偿语义；服务注册中心在这个运行切片通过测试后接入。

支付回调必须携带 `X-Payment-Timestamp`（Unix 秒）和 `X-Payment-Signature`。签名原文为
`orderId + "\n" + outTradeNo + "\n" + timestamp`，算法为 HMAC-SHA256，签名使用小写十六进制。
内部 Feign 调用自动携带 `X-Internal-Service-Token`；库存预占、释放、确认接口只保留在 `/internal/**`。

## 创建订单与幂等语义

创建订单的商品项必须包含单价：

```json
{
  "orderId": 980001,
  "userId": 880001,
  "totalAmount": 19.98,
  "timeoutSeconds": 1800,
  "items": [
    {"skuId": 980001, "spuId": 9800, "count": 2, "price": 9.99}
  ]
}
```

订单主表和 `order_item` 在 Order Service 的同一本地事务中提交。同一 `orderId` 重放时会比较用户、金额、超时时长和规范化后的全部商品字段：载荷相同则返回既有状态；载荷不同返回 409。若既有订单仍停在 `RESERVING`，服务会再次调用具备幂等语义的库存预占接口以恢复中断流程。

## 超时关单

Order Service 持久化 `timeout_seconds` 和 `expire_time`。扫描器只领取“待支付、库存已预占且已经到期”的订单，并通过条件更新把订单改为取消和待补偿；支付回调也使用状态条件更新，因此同一订单只有一个分支能够成功。库存释放复用补偿任务，Order 进程在关单提交后退出也不会丢失待释放状态。

默认超时为 1800 秒，允许范围为 1 秒到 7 天。该实现用索引范围扫描代替微服务内 Redis 延迟队列，当前优先保证重启可恢复和一致性；大规模订单下的分片扫描与容量测试仍待验证。

## Redis 快速下单与对账

`POST /api/orders/redis` 先把完整请求写入 `microservice_order_command`，然后调用 Inventory Service 的 Lua 脚本原子预扣多个 SKU。返回 202 表示命令已持久化并进入后台处理，不表示订单已经同步创建。后台任务以数据库租约领取命令，复用普通订单的 MySQL 预占链路，并通过条件更新安全重试。

对于连接中断等结果未知场景，Order Service 会请求强制补偿。Inventory Service 即使尚未看到预扣，也会写入同一订单和载荷的取消墓碑；晚到预扣会被拒绝，避免补偿先到、预扣后到造成库存泄漏。普通超时关单只执行条件补偿，不会为从未走过 Redis 路径的订单制造墓碑。

`GET /api/inventory/reconciliation` 根据 `MySQL 可售库存 - Inventory 账本中 PENDING 预扣量` 计算期望 Redis 值，只报告缺失和差异，不自动修复。Redis 预扣成功后先写 Inventory 自有账本；Order 的 MySQL 订单与锁定库存落库后，再幂等物化为 `MATERIALIZED`。投影调用失败只重试账本，不会回补已经形成有效订单的 Redis 库存。

## 当前功能边界

- 已迁移：订单主状态与明细、订单创建幂等、超时关单、库存预占/释放/确认、支付回调、订单 Outbox、取消栅栏、故障补偿、Redis 快速下单、异步落库和只读库存对账。
- 尚未迁入本切片：双层令牌桶和业务看板。
- Order 只访问 `fulfillment_order`，Inventory 只访问 `fulfillment_inventory`；两个最小权限账号的跨 schema 查询均被拒绝。当前仍是同一 MySQL 实例，不代表实例级故障隔离。
- 服务地址通过环境变量配置的静态 URL 提供，尚未接入 Nacos。
- 周期 11 后 Reactor 共 44 项自动化测试，覆盖服务逻辑、金额精度、到期任务竞争、Redis 幂等与取消墓碑、Inventory 预扣账本、异步命令状态机、Controller、Feign 契约和 Gateway 路由；跨进程双 schema 主链路结果来自本机联调记录。
- 周期 12 后四个微服务均依赖 Prometheus registry 并暴露 `/actuator/prometheus`；Order 暴露 Redis 命令和支付确认 Outbox 状态，Inventory 暴露 Redis 预占积压、最老积压年龄和最近一次对账差异。指标由定时刷新缓存，抓取不会直接访问数据库。
- 内部共享令牌和支付 HMAC 是本地切片的基础请求校验，不等同于 TLS、服务身份、密钥轮换和细粒度授权。
