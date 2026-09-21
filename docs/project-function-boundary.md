# 项目功能边界与对外口径

> 更新日期：2026-09-21  
> 本文是当前仓库功能边界的唯一事实入口。规划目标以 [`project-plan-v3.1.md`](project-plan-v3.1.md) 为准，完成状态以源码、自动化测试和 `docs` 下的验收证据为准。

## 1. 当前有两条运行路径

```mermaid
flowchart LR
    C[客户端] --> M[单体应用 :8080]
    M --> DB[(MySQL fulfillment)]
    M --> R[(Redis)]

    C --> G[Gateway :18080]
    G --> O[Order :18081]
    G --> P[Payment :18083]
    G --> I[Inventory 查询 :18082]
    P --> O
    O --> I
    O --> ODB[(MySQL fulfillment_order)]
    I --> IDB[(MySQL fulfillment_inventory)]
    O --> R[(Redis)]
    I --> R
```

- **根目录单体**：周期 0–6 的完整实验与回归基线，覆盖超时关单、Redis 快速下单、异步落库、对账、限流和业务看板。
- **`microservices` 运行切片**：周期 7–11 持续演进的四进程交易链路，验证 Gateway、Feign、服务间鉴权、Saga 补偿、跨进程 Outbox、订单创建幂等、持久化超时关单、Redis 快速下单和数据所有权隔离。
- 根目录单体继续使用 `fulfillment`；微服务 Order 和 Inventory 分别使用 `fulfillment_order`、`fulfillment_inventory`。三个 schema 当前位于同一个 MySQL 实例。

## 2. 功能矩阵

| 能力 | 根目录单体 | 微服务切片 | 当前结论 |
|---|---|---|---|
| MySQL 下单与多 SKU 预占 | 已实现；订单与库存处于同一本地事务 | 已实现；Order 保存状态后通过 Feign 调 Inventory | 两条路径都有，事务语义不同 |
| 库存预占、释放、确认、查询 | 已实现 | 已实现；变更接口仅在 `/internal/**` | 已覆盖 |
| 支付回调与幂等 | 已实现 | 已实现；增加 HMAC、时间窗和内部令牌 | 已覆盖 |
| 支付成功 Outbox | 本地调用 Inventory，至少一次投递 | Feign 调 Inventory，最多重试 10 次后进入死信 | 已覆盖；不等于 exactly-once |
| 超时关单与库存释放 | Redisson 延迟队列、重试和死信 | 周期 9 已实现持久化到期扫描与补偿释放 | 两条路径都有，调度机制不同 |
| Redis Lua 快速下单 | 已实现 | 周期 10 已实现；Order 持久化命令，Inventory 执行 Lua | 两条路径都有 |
| 异步订单落库与死信补偿 | 已实现 | 周期 10 已实现租约、重试和补偿终态 | 两条路径都有，状态表不同 |
| Redis/MySQL 库存对账 | 已实现 | 周期 10 已实现只读差异报告 | 两条路径都有，不自动修复 |
| 双层令牌桶限流 | 已实现，默认关闭 | 未迁移 | 单体独有 |
| 业务看板与自定义指标 | 已实现 | 仅有 Actuator 基础端点 | 单体独有 |
| Gateway 与 Feign 契约 | 无远程调用 | 已实现 | 微服务独有 |
| 库存取消栅栏 | 无 | 已实现，阻止晚到预占 | 微服务独有 |
| 订单查询接口 | 未提供 Controller | `GET /api/orders/{id}` | 微服务独有 |
| `order_item` 明细持久化 | 未实现 | 周期 8 已实现，与订单主表同一本地事务 | 微服务已覆盖 |
| 订单创建请求幂等 | 未实现 | 周期 8 已实现，同载荷重放、异载荷冲突 | 微服务已覆盖 |
| Nacos 服务发现与配置 | 未实现 | 未实现，使用固定 URL 环境变量 | 后续项 |
| 独立数据库/schema/账号 | 未实现 | 周期 11 已拆为两个 schema 和最小权限账号 | 已形成数据所有权边界；仍共用 MySQL 实例 |

## 3. 服务和数据所有权

| 进程/模块 | 默认端口 | 当前职责 | 实际访问的数据 |
|---|---:|---|---|
| `gateway` | 18080 | 对外路由 | 不访问数据库 |
| `order-service` | 18081 | 创建订单、预占状态、支付状态、Outbox、Redis 命令与补偿调度 | `order`、`order_item`、`order_outbox_event`、`microservice_order_command` |
| `inventory-service` | 18082 | 库存预占、释放、确认、Redis 原子预扣、查询、对账和取消栅栏 | `sku_stock`、`sku_stock_lock`、`inventory_reservation_fence`、`inventory_redis_reservation` |
| `payment-service` | 18083 | 校验支付回调并调用 Order | 不访问数据库 |
| `fulfillment-api` | - | DTO 与 Feign 契约 | 不包含实体或 Mapper |

这已经形成代码、进程、本地事务、schema 和账号边界。两个业务 schema 仍部署在同一 MySQL 实例，服务发现、多实例验证和生产运维体系尚未完成，因此当前仍称为**本地微服务运行切片**。

## 4. 一致性语义

### 单体路径

- 创建订单和库存预占在同一个 MySQL 本地事务中提交或回滚。
- 超时取消与库存释放在同一个 MySQL 本地事务中完成。
- 支付状态与 `PAYMENT_CONFIRMED` 事件在同一本地事务中写入。
- Outbox 在事务外投递，库存确认依靠状态条件更新吸收重复调用。
- Redis 预扣与 MySQL 落库之间没有分布式事务；已记录命令的失败路径可重试或补偿，对账用于发现仍未收敛的差异，不会自动修正。

### 微服务路径

- Order 与 Inventory 各自提交本地事务，不共享 `@Transactional`。
- 下单使用 `RESERVING → RESERVED / FAILED / PENDING_COMPENSATION / COMPENSATED` 状态推进；未知远程结果通过释放和后台补偿收敛。
- Inventory 使用 `orderId + skuId` 唯一约束、条件状态更新和取消栅栏保证接口幂等并处理释放早于预占的竞态。
- 支付状态与 Outbox 在 Order 的本地事务中提交，发布器通过 Feign 至少一次调用 Inventory；连续失败 10 次进入死信，当前需要人工排查和恢复。
- Redis 快速路径先持久化 Order 命令，再由 Inventory Lua 原子预扣；后台租约任务创建 MySQL 订单，失败时用载荷签名和取消墓碑幂等补偿。
- Inventory 自有账本记录 `PENDING / MATERIALIZED / COMPENSATED`；对账公式为 `MySQL 可售库存 - PENDING 账本预扣量`，不读取 Order 命令表，当前只输出差异，不自动改数。
- Redis 预扣与 MySQL 账本不是一个事务。账本写入失败时 Order 命令保持可恢复状态，重试 Redis 会命中幂等标记并补写账本。订单已经落库后，账本物化失败只重试投影，禁止走 Redis 回补。

## 5. 证据等级与可用表述

### 已有真实运行证据

- 周期 1：同配置并发实验中，先查后扣出现超卖，原子条件更新超卖为 0。
- 周期 2：真实 Redis 链路中，2 秒超时订单自动取消并完整释放 3 件锁定库存。
- 周期 4：40 个未排序事务出现 20 个死锁回滚；统一按 `skuId` 排序后，同轮 40 个事务无死锁回滚。
- 周期 5：单机三轮 JMeter 对比中，MySQL 路径中位吞吐 113.30 req/s，Redis 接受路径 342.91 req/s。Redis 的 HTTP 202 只表示命令被可靠接受。
- 周期 6：根目录单体的 Prometheus 采集目标为 `UP`，Grafana 数据源和看板可加载。
- 周期 7：本机四进程、共享 MySQL、固定 URL 条件下，完成下单、支付、重复回调、库存确认和库存服务宕机后的补偿联调。
- 周期 8：相同订单载荷重放不重复扣库存，异载荷返回冲突，订单明细完整持久化。
- 周期 9：2 秒未支付订单约 2.64 秒完成取消和库存释放，支付成功订单不会被到期扫描覆盖。
- 周期 10：真实 Gateway Redis 下单完成命令持久化、Lua 预扣和异步订单创建；30 秒到期后 MySQL 与 Redis 均恢复到 20，锁记录进入已释放，对账不再报告该 SKU。
- 周期 11：双 schema 与最小权限账号下，真实 Gateway Redis 下单完成预扣、异步落库和账本物化；3 秒到期后订单、MySQL 库存、Redis 库存和 Inventory 账本全部收敛，目标 SKU 对账无差异。

### 对外必须带上的限定

- “防超卖”限定为当前原子 SQL 和实测并发条件，不能外推为任意容量下都无问题。
- “死锁降为 0”限定为本次反向双 SKU 实验，不能描述为彻底消除所有数据库死锁。
- “微服务主链路已验收”限定为本机四进程、同一 MySQL 实例内双 schema、固定 URL 的运行切片。
- “Outbox 可靠投递”应表述为本地事务落库、至少一次投递、幂等消费、有限重试和死信。
- “安全加固”限定为共享内部令牌、支付 HMAC 和五分钟时间窗；不等于生产身份体系。
- 周期 11 的 44 项测试覆盖服务层、Controller、Feign 契约、Gateway 路由、Redis 幂等、Inventory 账本与异步命令状态机；真实跨进程结果来自单机联调记录。

### 当前不能声称已完成

- 生产级高可用、多实例无重复、零数据丢失或自动容灾。
- Nacos、负载均衡、灰度发布、Seata、RocketMQ 或 MySQL 实例级拆分。
- TLS、密钥轮换、服务身份、用户鉴权、细粒度授权和防重放存储。
- 微服务全量 Prometheus/Grafana、分布式追踪、集中日志、告警和 SLO。
- 周期 12 已完成四个微服务 Prometheus 指标端点与静态抓取配置；多实例告警、分布式追踪、集中日志和 SLO 仍未完成。
- 微服务容量结论；周期 5 的数据来自单机短时单体路径。
- 双层令牌桶和业务看板已经迁入微服务。
- 根目录单体的 `order_item` 已持久化或普通 MySQL 下单接口已经幂等。

## 6. 里程碑命名

所有当前说明统一使用“周期 0–11”，与规划书顺序对应：

- **周期 0**：环境与工程骨架。
- **周期 1**：超卖复现与原子扣减；早期文档曾简称 M0。
- **周期 2**：库存三态与超时关单；早期文档曾简称 M1。
- **周期 3**：单体业务边界、支付幂等和 Outbox。
- **周期 4**：多 SKU 死锁复现与排序修复。
- **周期 5**：Redis 预扣、异步落库、对账、限流和压测。
- **周期 6**：可观测性和运行看板。
- **周期 7**：在原规划之外追加的微服务运行切片。
- **周期 8**：微服务订单创建幂等与订单明细持久化。
- **周期 9**：微服务持久化超时关单与库存补偿释放。
- **周期 10**：微服务 Redis 快速下单、可靠异步落库与库存对账。
- **周期 11**：Order / Inventory schema、账号与对账数据所有权隔离。
- **周期 12**：微服务 Prometheus 指标与运行恢复信号。

“完成”默认只表示实现、自动化测试和对应验收证据完成；不包含规划书要求的个人面试盘问，也不代表生产就绪。

## 7. 下一阶段边界

建议按以下顺序推进：

1. 接入服务发现，并补多实例 Outbox 和真实故障注入。
2. 为 Inventory 账本投影增加告警与受保护的人工恢复入口。
3. 评估双层令牌桶和业务看板是否迁移，先定义微服务容量与运维验收指标。

## 8. 证据索引

- `docs/cycle4-evidence-2026-09-19.md`
- `docs/redis-timeout-e2e-2026-09-19.md`
- `docs/cycle5-evidence-2026-09-19.md`
- `docs/cycle6-observability-2026-09-19.md`
- `docs/cycle7-microservices-evidence-2026-09-21.md`
- `docs/cycle8-order-idempotency-evidence-2026-09-21.md`
- `docs/cycle9-timeout-close-evidence-2026-09-21.md`
- `docs/cycle10-redis-microservice-evidence-2026-09-21.md`
- `docs/cycle11-schema-isolation-evidence-2026-09-21.md`
- `docs/cycle12-observability-evidence-2026-09-21.md`
- `docs/audit-remediation-2026-09-19.md`
