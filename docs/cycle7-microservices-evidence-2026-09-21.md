# 周期 7 微服务运行切片验收

## 运行边界

- Gateway：`18080`
- Order Service：`18081`
- Inventory Service：`18082`
- Payment Service：`18083`
- MySQL：本机 `fulfillment` 数据库
- 服务调用：OpenFeign，地址由环境变量配置

根目录单体仍保留，用于复现周期 1–6 的并发实验和功能。`microservices` 是独立 Maven Reactor，其中 Gateway、Order、Inventory、Payment 是四个可启动进程，`fulfillment-api` 是契约模块。

## 自动化验证

执行整个 Reactor 的 `mvn test`：

- Inventory：8 项
- Order：8 项，其中包括 Feign 契约解析、外部交易号冲突和 Outbox 发布重试
- Payment：3 项
- Gateway：1 项
- 合计：20 项，0 failures，0 errors，`BUILD SUCCESS`

这些测试主要覆盖 Mockito 服务逻辑、Controller、Feign 契约解析和 Gateway 路由上下文，不是 20 项端到端测试。下文的跨进程结果来自 2026-09-21 的本机手工联调。

首次真实启动发现类级 `@RequestMapping` 不被 Spring Cloud OpenFeign 4.1.3 接受。契约已改为方法级完整路径，
并加入 `FeignContractTest`，防止只跑 Mockito 单元测试时再次漏掉启动期错误。

## 主链路实测

测试 SKU `970001` 初始为可售 20、锁定 0，订单号 `970001`：

1. 通过 Gateway 创建订单，返回 `RESERVED`。
2. 库存变为可售 18、锁定 2。
3. 通过 Payment Service 提交支付成功回调，返回 `ACCEPTED`。
4. Order Service 在支付事务内写入 `PAYMENT_CONFIRMED` Outbox。
5. 发布器调用 Inventory Service 确认库存，库存变为可售 18、锁定 0。
6. 重复提交相同支付回调仍返回 `ACCEPTED`；Outbox 查询结果为 1 条且状态为已发送，没有重复确认库存。

最终订单状态：`PAID / RESERVED`，外部交易号 `PAY-CYCLE7-970001`。这里的 `RESERVED` 是订单侧“预占阶段已成功”记录；库存锁定量已经由确认操作降为 0，不表示库存仍被占用。

## 故障与补偿实测

关闭 Inventory Service 后通过 Gateway 创建订单 `970002`：

- Order Service 无法判断远端是否曾完成预占，立即尝试幂等释放。
- 释放同样失败，订单进入 `PENDING_COMPENSATION`，错误原因记录连接拒绝。
- Inventory Service 恢复后，后台补偿任务调用释放接口。
- 由于库存侧没有这笔预占，幂等释放仍返回成功，订单转为 `COMPENSATED`。

库存明确不足的订单 `970003` 返回确定失败，最终状态为 `CANCELED / FAILED`，不会接受支付。

## 当前限制

- 当前先用可配置 URL 验证真实网络边界，尚未接入 Nacos 服务发现。
- Order 与 Inventory 仍连接同一个 MySQL 实例，但代码和事务已经分进程；后续可进一步拆 schema 与账号权限。
- Outbox 使用数据库状态抢占与一分钟陈旧任务回收，已完成单实例路径和局部幂等测试；并发重复发布与多实例长时间故障注入仍需单独验证。
- 超时关单、Redis 快速下单和周期 5 对账仍由根目录单体承载，后续逐条迁移，不能把周期 7 描述成全部能力已经拆完。

## 审计后加固

- 增加 `inventory_reservation_fence` 取消栅栏：释放先到会留下取消墓碑，晚到的预占无法再扣库存；
  预占先到时，释放会等待同一订单栅栏锁后再释放，关闭“先释放成功、后预占提交”的永久锁库窗口。
- 外部库存路由只保留查询，预占、释放、确认仅位于 `/internal/**` 并校验内部服务令牌。
- 支付回调增加 5 分钟时间窗和 HMAC-SHA256 签名验证；Feign 内部调用统一携带服务令牌。
- Feign 明确设置 1 秒连接超时和 3 秒读取超时；Outbox 连续失败 10 次进入死信状态。
- 迁移脚本已连续执行两次成功，并按旧订单状态与锁定记录回填预占状态和取消栅栏。
- 加固后的单机手工联调验证：四个端口均为 `UP`；无内部令牌调用库存变更接口返回 401，Gateway 上不存在
  外部库存变更路由；错误支付签名返回 401，正确签名完成支付并由 Outbox 把锁定库存从 2 确认到 0。

共享内部令牌、HMAC 和时间窗属于本地切片的基础请求校验，未覆盖 TLS、服务身份、密钥轮换、细粒度授权和重放记录。周期 6 的 Prometheus/Grafana 验收针对根目录单体，尚未覆盖四个微服务进程。
