# 周期 7：微服务运行切片

这个目录是从现有单体逐步拆分出的独立运行路径。根目录单体继续作为 M0-M6 的可复现实验基线，
这里用于验证真实网络边界、服务故障和补偿流程。

单体与微服务切片当前共用数据库表。联调微服务时请停止根目录单体，避免两个 Outbox 发布器同时消费
`order_outbox_event`；这也是后续物理拆分 schema 前必须遵守的运行约束。

## 服务与端口

| 模块 | 默认端口 | 职责 |
|---|---:|---|
| `gateway` | 18080 | 统一入口与路由 |
| `order-service` | 18081 | 订单创建、预占状态、支付状态 |
| `inventory-service` | 18082 | 库存预占、释放、确认和查询 |
| `payment-service` | 18083 | 支付成功回调入口 |
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
`PAYMENT_CONFIRMED` 本地消息在订单服务的同一事务提交，Outbox 发布器随后调用库存确认接口，失败按
指数退避重试。因此支付进程或库存进程短暂故障不会形成“已支付但确认消息永久丢失”的窗口。

## 本地构建

```powershell
$env:MYSQL_PASSWORD = '<本机 MySQL 密码>'
$env:INTERNAL_SERVICE_TOKEN = '<订单、库存和支付服务共用的内部调用令牌>'
$env:PAYMENT_CALLBACK_SECRET = '<支付回调 HMAC 密钥>'
& 'D:\vibecoding\.toolchains\maven\apache-maven-3.9.16\bin\mvn.cmd' `
  '-Dmaven.repo.local=D:\vibecoding\.m2\repository' `
  -f .\microservices\pom.xml test
```

首次运行前执行 `sql/migration-cycle7.sql`。各服务通过环境变量配置依赖地址，默认指向上表中的本机端口。
当前阶段先用明确的服务 URL 验证网络和补偿语义；服务注册中心在这个运行切片通过测试后接入。

支付回调必须携带 `X-Payment-Timestamp`（Unix 秒）和 `X-Payment-Signature`。签名原文为
`orderId + "\n" + outTradeNo + "\n" + timestamp`，算法为 HMAC-SHA256，签名使用小写十六进制。
内部 Feign 调用自动携带 `X-Internal-Service-Token`；库存预占、释放、确认接口只保留在 `/internal/**`。
