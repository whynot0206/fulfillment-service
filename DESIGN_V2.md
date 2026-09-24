# 高并发电商平台 V2 设计文档

> 文档状态：下一版本迭代设计
>
> 事实口径：当前代码与测试优先，docs/project-function-boundary.md 是现状边界入口；本文中的“目标”“计划”“V2”表示尚未全部落地的设计。2026-09-24 的 MVP 实现与本机验收见 docs/mvp-v2-test-evidence-2026-09-24.md。
> 后续增量：用户主动取消和本地模拟支付已实现，验证见 docs/v2-order-actions-evidence-2026-09-24.md；Payment 持久化支付单仍属于未完成设计。

## 1. 项目定位

项目统一定位为：**高并发电商平台**。

平台提供商品浏览、购物车、订单、库存和模拟支付等核心电商能力，重点建设从商品到履约的真实交易链路：

~~~text
商品 / SKU
   -> 购物车
   -> 提交订单
   -> 库存预占
   -> 待支付订单
   -> 支付确认
   -> 库存确认 / 订单履约
~~~

V2 的技术重点不是堆叠中间件，而是解决这条链路在大促和故障条件下的真实问题：热点库存超卖、多 SKU 并发死锁、重复下单、重复支付回调、支付与超时关单竞争、消息重复投递、Redis 与 MySQL 异步不一致以及服务重启后的恢复。

当前仓库已经有较完整的库存与履约核心，但还缺商品、购物车、用户入口和面向消费者的完整 Web API。因此 V2 是在现有履约核心上补齐“可使用的商城外壳”，并把实验代码收敛为可演示、可测试、可部署的业务系统。

## 2. 当前版本目标

V2 必须完成以下闭环：

1. 用户可以浏览商品和 SKU，查看价格及可售库存。
2. 用户可以添加、修改、删除购物车商品，并校验商品和价格快照。
3. 用户可以使用幂等键提交订单，订单服务完成商品校验和库存预占。
4. 订单进入待支付状态，支付服务提供模拟支付单和签名回调。
5. 支付成功后，订单变为已支付，库存从锁定转为已确认。
6. 超时未支付订单自动关闭并释放库存。
7. 重复下单、重复支付、重复回调、重复取消、重复消费和服务重启都不会重复扣减库存。
8. 失败的远程调用和消息投递进入可恢复的重试、补偿或死信状态。
9. 前端能够完整演示上述业务流程，但前端不承载交易一致性逻辑。

V2 的完成标准以业务行为为中心：请求能否正确结束、失败能否恢复、重试是否幂等、状态是否可追踪，而不是以服务数量或依赖数量为指标。

## 3. 非目标与系统边界

V2 暂不实现以下能力：

- 优惠券、促销计算、积分、会员等级和营销活动；
- 多商户、商家后台、采购和供应链；
- 搜索集群、推荐系统、复杂商品审核和内容系统；
- 物流、发货、退款、售后和评价；
- 对接真实微信/支付宝，支付使用可验签的模拟支付服务；
- 分库分表、跨地域多活、Kubernetes 服务网格和全套云原生平台；
- 生产级风控、实名、复杂 RBAC 和金融级支付清算。

这些能力可以进入后续版本，但不能在 V2 中创建没有实现价值的空模块。V2 的商品模型只服务于下单和库存，支付模型只服务于订单状态和库存确认。

## 4. 当前系统现状分析

### 4.1 已实现能力

根目录单体当前默认运行在 8080，使用 fulfillment 数据库，已经实现：

- 订单创建、库存三态、SKU 预占、释放和确认；
- 支付成功回调、外部交易号唯一约束和订单状态条件更新；
- Redisson 延迟关单、本地消息表 Outbox、异步订单命令、租约、退避和死信；
- Redis Lua 多 SKU 原子预扣、幂等标记、失败补偿、令牌桶限流和只读对账；
- Micrometer、Actuator、Prometheus、Grafana 和单体运行看板；
- 真实 MySQL 超卖、死锁、Redis 端到端、JMeter 压测和故障分支测试。

microservices/ 当前包含四个运行进程和一个契约模块：

| 模块 | 端口 | 已有职责 |
| --- | ---: | --- |
| gateway | 18080 | Spring Cloud Gateway 路由 |
| order-service | 18081 | 订单创建、明细、状态、支付、Outbox、超时关单、异步命令和补偿 |
| inventory-service | 18082 | 库存预占、释放、确认、Redis Lua、账本、取消栅栏和对账 |
| payment-service | 18083 | 支付回调时间窗、HMAC 校验和 Order 调用 |
| fulfillment-api | - | Feign 接口和 DTO，不含实体和 Mapper |

### 4.2 可以直接复用的部分

- InventoryReservationServiceImpl 的 SKU 合并、排序、锁定记录和状态条件更新；
- SkuStockMapper.xml 中的带库存条件原子更新；
- 单体和微服务的库存状态、取消栅栏、Redis Lua、账本和对账思路；
- OrderApplicationService 的订单状态与远程库存结果处理；
- Outbox 条件抢占、陈旧任务恢复、指数退避和死信状态；
- 真实并发、死锁、Redis、Feign 契约和 Controller 测试；
- Micrometer 指标、Prometheus 配置、Grafana 看板和 Docker Compose 依赖。

### 4.3 当前属于实验基线的部分

- 根目录中的 StockConcurrencyTest 和未安全的先查后扣实现只用于复现实验，不应进入消费者请求路径；
- 根目录普通 MySQL 下单接口没有微服务版本的完整 order_item 持久化和请求幂等语义；
- 单体直接通过 InventoryController 读取 SkuStockMapper，绕过了库存 Service；
- 单体与微服务存在两套命令表、账本和调度实现，适合对照验证，但不适合作为同一线上部署共同消费；
- 当前微服务使用固定 URL、共享 MySQL 实例和本地定时任务，尚未证明多实例抢占、容量和实例级故障隔离；
- 当前没有商品、购物车、用户和前端，尚不能称为完整电商平台。

## 5. 当前架构存在的问题

1. **业务入口不完整**：下单需要直接构造请求，缺少商品查询、购物车校验、价格快照和消费者 API。
2. **运行路径重复**：单体和微服务各有一套订单命令、库存 Redis 适配和调度器，边界容易被误用。
3. **普通单体订单不完整**：根目录 order 初始化表有 order_item，但普通单体下单代码没有写入明细，也没有微服务版本的同载荷重放冲突。
4. **Controller 职责不完全清晰**：单体库存查询直接注入 Mapper；部分 Controller 通过手写 if 重复校验。
5. **支付模型偏薄**：微服务 Payment 当前接收回调并调用 Order，没有独立支付单状态和支付回调记录，重复回调审计能力不足。
6. **消息机制尚未形成生产链路**：当前是数据库 Outbox + 定时发布，可靠性适合单机验证；没有统一事件命名、消息 Broker、积压监控和人工重放入口。
7. **数据所有权已开始隔离但实例仍共享**：Order 和 Inventory 有独立 schema 与账号，但仍位于同一个 MySQL 实例。
8. **安全边界有限**：内部共享令牌和支付 HMAC 是本地切片的基础校验，不等于用户登录、TLS、密钥轮换和服务身份体系。
9. **可观测性不完整**：已有指标端点，但缺请求关联 ID、分布式追踪、集中日志、告警和业务 SLO。
10. **前端缺失**：没有真实消费者操作界面，无法通过端到端流程验证购物车、价格变化、重复提交和订单查询。

## 6. V2 整体架构设计

V2 不继续拆出大量微服务。采用“一个商城业务模块 + 三个履约核心服务”的结构：

~~~text
Vue 3 前端
       -> Nginx / Gateway
            -> commerce-service（用户、商品、购物车，模块化单体）
            -> order-service
            -> payment-service
            -> inventory-service

order-service       -> Order DB + Outbox / MQ
inventory-service   -> Inventory DB + Redis
payment-service     -> Payment DB + Order API
~~~

### 6.1 为什么保留当前三个核心服务

- Inventory 有独立的热点行、Redis Lua、库存账本和对账需求，具备独立部署价值；
- Order 有订单状态、幂等、Outbox、关单和补偿需求，具备独立部署价值；
- Payment 具有外部回调、签名校验和不同安全边界，保持薄服务便于替换模拟支付；
- Gateway 只做入口路由，不新增业务职责。

### 6.2 为什么用户、商品和购物车先合并

V2 的用户、商品和购物车规模有限，拆成三个服务会增加契约、部署和数据同步成本。将它们放进 commerce-service 的模块化单体，可以保持业务闭环易懂；未来商品读流量或购物车规模达到独立部署条件时，再按接口拆分。

### 6.3 数据所有权

| 模块 | 事实数据 | 允许访问 |
| --- | --- | --- |
| commerce | 用户、商品、SKU 基础信息、购物车 | 自有数据库；调用 Inventory 查询可售库存 |
| order | 订单、订单明细、订单状态、Outbox、幂等记录 | 自有数据库；通过契约调用 Inventory/Payment |
| inventory | 可售库存、锁定记录、Redis 预扣账本、对账 | 自有数据库和 Redis |
| payment | 支付单、回调记录、支付状态 | 自有数据库；通过契约调用 Order |

任何服务不得跨库读取其他服务表。跨模块信息通过 API、事件或明确的只读 DTO 获取。

## 7. 核心业务模块划分

### User

V2 只实现用户注册/登录的最小闭环、用户 ID 和收货信息快照。认证可使用 JWT 或服务端 Session；用户模块不负责订单状态和库存。

### Product

Product 管理 SPU、SKU、价格、上下架和商品展示信息。Product 只负责商品事实，不负责扣库存。下单时将 SKU、SPU、名称、单价和规格快照写入订单，避免后续改价影响历史订单。

### Cart

Cart 负责用户购物车中的 SKU、数量和选中状态。购物车可用 Redis 保存热数据，必要时异步持久化；提交订单时必须重新查询商品状态、价格和库存，不能信任购物车中的旧价格或旧库存。

### Order

Order 编排下单、订单状态、订单明细、支付状态、超时关闭、取消和 Outbox。订单服务不直接写库存。

### Inventory

Inventory 维护可售、锁定和已确认消耗三种业务含义，实际落库继续使用当前 stock、lock_stock 与锁定记录模型。库存是履约核心，负责所有预占、确认、释放和对账。

### Payment

Payment 管理模拟支付单、支付状态、外部交易号、回调签名和回调审计。支付成功通过 Order 契约推进订单，不直接修改库存。

## 8. 商品模型设计

V2 使用 SPU + SKU 两层模型：

- product_spu：商品名称、描述、品牌、上下架状态；
- product_sku：SKU 编码、SPU、规格 JSON、销售价、状态；
- product_image：商品图片地址和排序；
- product_category：有限层级分类，仅用于展示和筛选。

商品价格以 DECIMAL(12,2) 保存。订单创建时必须把商品名称、SKU、规格和价格复制到 order_item，商品下架或改价不影响已创建订单。SKU 的库存数量只在 Inventory 服务维护，Product 只能查询展示数据。

## 9. 购物车设计

购物车接口：

~~~text
GET    /api/cart
POST   /api/cart/items
PUT    /api/cart/items/{skuId}
DELETE /api/cart/items/{skuId}
~~~

Redis Key 使用 cart:{userId}，Hash 或 JSON 结构保存 SKU、数量和选中状态，并设置合理过期时间。购物车数据不是订单事实；提交订单时必须：

1. 校验用户身份和购物车归属；
2. 查询商品和 SKU 当前状态、价格；
3. 过滤下架商品和非法数量；
4. 将最终商品快照发送给 Order；
5. Order 再次调用 Inventory 预占，不能把购物车校验当成库存锁定。

Redis 故障时，购物车可以返回可重试错误或降级为空；不能因为购物车缓存缺失而扣库存。

## 10. 订单模型与订单状态机

订单主状态：

~~~text
CREATING
   -> PENDING_PAYMENT
       -> PAID
           -> COMPLETED（V2 可先由履约确认触发）
       -> CANCELED（用户取消或超时）
~~~

库存预占状态独立保存：

~~~text
RESERVING -> RESERVED
RESERVING -> FAILED
RESERVED  -> RELEASE_PENDING -> RELEASED
RESERVED  -> CONFIRM_PENDING -> CONFIRMED
~~~

远程调用结果未知时使用 PENDING_COMPENSATION，不能直接改成 FAILED。合法操作：

- 创建只允许从不存在订单进入 CREATING；同一幂等键和同载荷重放返回既有订单；不同载荷返回冲突。
- 支付只允许 PENDING_PAYMENT + RESERVED 进入 PAID。
- 取消只允许 PENDING_PAYMENT + RESERVED 进入 CANCELED，并触发释放。
- PAID、CANCELED、COMPLETED 是终态或受限状态，重复请求返回幂等结果。
- 支付与关单使用同一条条件更新竞争，只有一个事务能成功推进状态。

## 11. 库存模型与库存状态机

业务模型：

~~~text
available  可售库存
locked     已被订单预占、尚未支付
sold       已支付确认，不再可售
~~~

实际变化：

~~~text
下单成功：available -> locked
支付成功：locked -> sold
订单取消：locked -> available
~~~

单体和 Inventory 服务继续使用：

- sku_stock.stock：可售库存；
- sku_stock.lock_stock：预占库存；
- sku_stock_lock：订单、SKU、数量和 LOCKED/RELEASED/CONFIRMED 状态；
- inventory_redis_reservation：Redis 预扣账本的 PENDING/MATERIALIZED/COMPENSATED 状态。

库存预占必须在一次事务或一次 Lua 执行中完成全量校验和修改。多 SKU 先合并重复 SKU，再按 skuId 升序处理。释放和确认必须用状态条件更新，重复调用不得再次加减库存。

Redis 预扣不是最终库存事实。Order 先记录可恢复命令，Inventory 记录自己的 Redis 账本；订单落库后物化，明确失败终态才补偿，未知结果继续重试。对账只报告差异，修复必须通过受保护的补偿命令完成。

## 12. 支付模型

新增 payment_order 和 payment_callback_record：

- payment_order：支付单号、订单号、金额、状态、外部交易号、创建和更新时间；
- payment_callback_record：外部交易号、回调原文摘要、签名校验结果、处理状态和最后错误；
- out_trade_no 唯一，回调请求可重复到达；
- 支付单状态：CREATED -> PAYING -> SUCCESS / CLOSED。

模拟支付流程仍按真实支付边界设计：创建支付单、模拟支付成功、生成带时间戳和 HMAC 的回调、Payment 验签、条件更新支付单、调用 Order 标记支付、重试库存确认。Payment 不直接修改 Order 或 Inventory 数据库。

## 13. 下单完整时序

~~~mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway
    participant M as Commerce
    participant O as Order
    participant I as Inventory
    participant DB as Order DB

    C->>G: POST /api/orders + Idempotency-Key
    G->>M: 校验用户与购物车
    M->>O: 商品/SKU/价格快照
    O->>DB: 条件创建 CREATING/PENDING_PAYMENT
    O->>I: reserve(orderId, items)
    alt 预占成功
        I-->>O: RESERVED
        O->>DB: 提交订单、明细和 RESERVED
        O-->>C: 201 PENDING_PAYMENT
    else 库存不足
        I-->>O: REJECTED
        O->>DB: FAILED
        O-->>C: 409
    else 结果未知
        I-->>O: timeout/unknown
        O->>DB: PENDING_COMPENSATION
        O-->>C: 202/503 可重试
    end
~~~

推荐 V2 的顺序是“先持久化订单意图，再调用库存”，这样结果未知时有订单状态可恢复；库存接口必须以 orderId + SKU + payload signature 幂等。对于 Redis 快速路径，命令先持久化，再由 Inventory 执行 Lua 预扣，避免“Redis 已扣但没有可靠事实”的窗口。

## 14. 支付完整时序

~~~mermaid
sequenceDiagram
    participant U as User
    participant P as Payment
    participant O as Order
    participant I as Inventory
    participant E as Outbox/MQ

    U->>P: 创建支付单
    P-->>U: paymentId
    U->>P: 模拟支付成功
    P->>P: 校验时间窗和 HMAC
    P->>O: markPaid(orderId, outTradeNo)
    O->>O: 条件更新 PENDING_PAYMENT -> PAID
    O->>O: 同事务写 PAYMENT_CONFIRMED
    O-->>P: 幂等成功/拒绝
    E->>I: confirm(orderId)
    I->>I: LOCKED -> CONFIRMED
    I-->>E: 确认成功或可重试失败
~~~

重复回调在支付单和订单状态层都返回幂等成功；不同订单复用同一个外部交易号必须返回业务冲突。Outbox 投递失败进入重试和死信，不能让支付回调同步等待库存确认。

## 15. 超时关单流程

订单到期后由持久化扫描或消息触发：

~~~text
发现 PENDING_PAYMENT + RESERVED + expire_time <= now
    -> 条件更新为 CANCELED + RELEASE_PENDING
    -> 调用 Inventory.release(orderId)
    -> 成功：RELEASED / COMPENSATED
    -> 失败：保留 RELEASE_PENDING，指数退避重试
~~~

支付和关单竞争同一订单条件更新。支付先成功时，关单更新影响行数为 0，不能释放库存；关单先成功时，支付回调返回拒绝或幂等结果。重复扫描和重复释放都依靠订单状态、库存锁定状态和唯一键兜底。

## 16. 库存预占、确认与释放流程

### 预占

1. 校验订单号、SKU、数量和 SPU 关系；
2. 合并重复 SKU 并排序；
3. 创建或读取 sku_stock_lock；
4. 使用 stock >= count 的条件更新扣减可售库存、增加锁定库存；
5. 任意 SKU 失败时回滚整个本地事务；
6. 微服务场景把远程结果写入订单状态，未知结果进入补偿。

### 确认

支付确认只处理 LOCKED 记录，条件更新为 CONFIRMED 后减少 lock_stock。重复确认直接返回已确认，已释放记录拒绝确认。

### 释放

取消或超时只处理 LOCKED 记录，条件更新为 RELEASED 后增加 stock、减少 lock_stock。释放先到时写取消栅栏，晚到的预占必须拒绝；释放失败保留补偿事实，不在调用方直接假设成功。

## 17. 幂等设计

| 场景 | 幂等键 | 存储与判断 |
| --- | --- | --- |
| 创建订单 | Idempotency-Key + 用户 | Order DB 唯一键和规范化请求摘要 |
| 订单业务重放 | orderId | 比较用户、金额、超时和完整明细；同载荷返回既有状态，异载荷 409 |
| 库存预占 | orderId + skuId | 锁定记录唯一键、载荷校验和状态机 |
| 支付回调 | outTradeNo | Payment 唯一约束 + Order 条件更新 |
| Outbox 消费 | eventId/orderId | 消费端状态条件更新 |
| Redis 预扣 | orderId + payload signature | Lua marker、取消墓碑和 Inventory 账本 |
| 取消/释放 | orderId | 订单状态、锁定记录和补偿状态 |

幂等记录必须有保存期限或清理策略。幂等成功和业务拒绝必须区分，避免调用方把已完成操作当成失败而无限重试。

## 18. 并发控制设计

- 热点库存：Redis Lua 做快速全量校验和预扣，MySQL 条件更新作为持久化库存约束；不使用先查后扣。
- MySQL 多 SKU：合并重复 SKU，按 skuId 升序取得行锁，统一预占、释放和确认顺序。
- 订单创建：唯一键或幂等记录先抢占请求，避免同一业务键并发创建多个订单。
- 支付与关单：同一订单条件更新竞争，数据库影响行数决定唯一胜者。
- 多实例任务：数据库租约或消息消费组抢占，租约过期可恢复；任务处理必须接受重复执行。
- 计数和金额：库存数量使用整数并校验溢出，金额使用 BigDecimal；禁止用浮点数作为订单金额。
- 不把分布式锁作为所有问题的默认方案。先使用唯一键、条件更新和确定的锁顺序；确有跨资源临界区时才使用 Redisson 锁，并定义租约和故障释放。

## 19. 分布式事务与最终一致性

强一致边界：

- 同一服务数据库中的订单、订单明细、支付状态、库存锁定记录和 Outbox 事件；
- 同一事务中的状态条件更新和唯一约束；
- Inventory 本地事务中的库存主表、锁定记录、取消栅栏和账本状态。

最终一致边界：

- Order 与 Inventory 的远程预占、释放和确认；
- Payment 回调后的库存确认；
- Redis 预扣与 MySQL 订单/库存物化；
- Outbox/MQ 事件投递和消费。

最终一致性的统一模式是：**事实先落库，事件可重试，消费幂等，失败可补偿，长期差异可对账**。不使用跨服务 @Transactional，不声称 exactly-once。

## 20. MQ 使用边界

当前仓库没有 RabbitMQ/Kafka 依赖，已有数据库 Outbox 和调度器。V2 先保留 Outbox 作为可靠事实，再引入 RabbitMQ 作为跨服务事件传输，原因是本项目事件量和路由复杂度有限，RabbitMQ 的确认、重试和死信更容易在本地部署和演示。

### 适合进入 MQ 的事件

- OrderCreated、PaymentConfirmed、OrderCanceled；
- InventoryReserved、InventoryReleased、InventoryConfirmed；
- 只读通知和运营统计事件。

### 不直接进入 MQ 的动作

- 不能用 MQ 代替库存条件更新；
- 不能让消费者直接修改其他服务数据库；
- 不能把用户请求的同步错误隐藏成无期限消息；
- 不能用 MQ 代替订单幂等和库存状态机。

生产者先在本地事务写 Outbox，再由发布器投递 RabbitMQ；Broker 使用 publisher confirm、持久化消息、消费确认、指数退避和死信队列。消费者以 eventId 或业务键去重，消息积压暴露指标并支持人工重放。V2 P0 可以继续使用数据库 Outbox 调度，P1 再接 Broker，不应为了“有 MQ”而提前阻塞核心闭环。

## 21. Redis 使用边界

Redis 只承担适合缓存或快速协调的工作：

- 商品详情和列表的短时缓存；
- 用户购物车热数据；
- 热点 SKU 的 Lua 预扣和幂等标记；
- 令牌桶限流；
- 短期幂等键和任务辅助锁。

MySQL 仍是订单、支付、商品和库存最终事实。所有 Redis 写入都要有过期、回补、重建或对账策略。Redis Lua 多键脚本当前面向单实例；迁移 Cluster 时必须通过 hash tag 保证同一订单相关键在同一槽，或重新设计脚本和补偿流程。

## 22. 数据库表设计

V2 建议按数据所有权拆分表，不要求一次性物理拆分成多个 MySQL 实例。

### Commerce DB

~~~text
user_account(id, username, password_hash, status, create_time, update_time)
user_address(id, user_id, address_snapshot, is_default, create_time, update_time)
product_spu(id, name, description, category_id, status, create_time, update_time)
product_sku(id, spu_id, sku_code, spec_json, price, status, create_time, update_time)
product_image(id, spu_id, url, sort_order)
cart_item(id, user_id, sku_id, quantity, selected, create_time, update_time)
~~~

### Order DB

~~~text
sales_order(order_id, user_id, total_amount, status, reservation_status,
            expire_time, idempotency_key, request_digest, create_time, update_time)
order_item(id, order_id, spu_id, sku_id, name_snapshot, spec_snapshot,
           price, quantity, create_time)
payment_order(id, order_id, amount, status, out_trade_no, create_time, update_time)
order_outbox_event(id, event_type, biz_key, payload, status, retry_count,
                   next_retry_time, lease_owner, lease_until, last_error)
idempotency_record(idempotency_key, user_id, request_digest, order_id,
                   status, expire_time, create_time)
~~~

### Inventory DB

~~~text
sku_stock(sku_id, spu_id, stock, lock_stock, version, update_time)
sku_stock_lock(id, order_id, sku_id, spu_id, count, status, create_time, update_time)
inventory_reservation_fence(order_id, status, update_time)
inventory_redis_reservation(order_id, items_json, signature, status,
                            retry_count, last_error, create_time, update_time)
~~~

现有 sku_stock、sku_stock_lock 和 Redis 账本应通过兼容迁移保留；不要把 V2 的产品展示表和库存事实混在一起。现有表名 order 为 SQL 保留字，改名为 sales_order 只能通过兼容迁移并同步 Mapper，不能直接删除旧表。

## 23. 接口设计

### 商品与购物车

~~~text
GET  /api/products
GET  /api/products/{spuId}
GET  /api/products/{spuId}/skus
GET  /api/cart
POST /api/cart/items
PUT  /api/cart/items/{skuId}
DELETE /api/cart/items/{skuId}
~~~

### 订单

~~~text
POST /api/orders
GET  /api/orders/{orderId}
GET  /api/orders
POST /api/orders/{orderId}/cancel
~~~

POST /api/orders 必须携带 Idempotency-Key，请求包括用户、收货地址快照、商品 SKU、数量和客户端价格摘要。服务端以商品当前价格为准，返回订单状态和支付截止时间。

### 支付

~~~text
POST /api/payments
POST /api/payments/{paymentId}/mock-success
POST /api/payments/callbacks/success
GET  /api/payments/{paymentId}
~~~

### 内部库存

~~~text
POST /internal/inventory/reserve
POST /internal/inventory/release
POST /internal/inventory/confirm
POST /internal/inventory/redis/reserve
POST /internal/inventory/redis/compensate
GET  /internal/inventory/query/{skuId}
~~~

内部接口继续使用服务间认证；外部 Gateway 不暴露库存变更接口。所有接口必须定义成功、拒绝、未知和待补偿语义，不能只依赖 HTTP 200 表示业务成功。

## 24. 异常处理与补偿机制

异常分类：

- 参数和协议错误：400；
- 幂等冲突、库存不足、非法状态：409 或稳定的业务错误码；
- 外部调用超时或结果未知：202/503，并保存 PENDING_COMPENSATION；
- 数据库、Redis 或消息 Broker 暂时故障：记录上下文，交由重试调度器处理；
- 达到最大重试次数：进入死信，保留人工恢复所需的订单号、事件号、命令号和最后错误。

补偿任务必须满足：

1. 只处理明确的待补偿状态；
2. 使用租约或消息消费确认，允许任务重启恢复；
3. 每次重试有上限和退避，避免故障时打爆下游；
4. 补偿前再次检查当前订单和库存状态；
5. 补偿成功后使用条件状态迁移，不能覆盖其他并发分支；
6. 死信有查询、重放和审计入口。

## 25. 日志与可观测性

日志统一使用 SLF4J，至少关联 requestId、orderId、paymentId、eventId、commandId 和 traceId（接入追踪后）。不记录密码、Token、支付签名、Authorization Header 和完整用户地址。

核心指标：

- 请求量、成功率、P95/P99、4xx/5xx；
- 下单成功、库存不足、幂等重放、支付成功和超时关单；
- Redis 预扣、MySQL 物化、Outbox/MQ 积压、重试和死信；
- Inventory 对账差异、补偿成功率和最老积压年龄；
- 数据库连接池、Redis 命中率、消息消费延迟。

V2 P2 增加 OpenTelemetry 或等价链路追踪，统一采集 Gateway、Order、Inventory、Payment 的 trace。Prometheus label 保持低基数，订单号和用户 ID 不作为 label。

## 26. Docker 与部署架构

### 本地开发

Docker Compose 提供：

~~~text
frontend/nginx
gateway
commerce-service
order-service
inventory-service
payment-service
mysql（可先单实例、多 schema）
redis
rabbitmq（P1 引入）
prometheus
grafana
~~~

本地 Compose 用于复现业务链路和故障，不等于生产部署。所有服务通过环境变量配置地址、账号、密钥和超时；镜像不内置真实凭证。

### 生产化演进

1. 单机/单节点 staging：多进程容器、独立 schema、健康检查和滚动启动；
2. 多实例服务：Gateway、Commerce、Order、Payment、Inventory 至少两副本，任务使用租约或消费组；
3. 数据层高可用：MySQL 主从/托管实例、Redis Sentinel 或 Cluster、RabbitMQ 持久化与镜像队列；
4. 运维层：TLS、密钥管理、集中日志、追踪、告警、备份恢复和灰度发布。

V2 不把 Kubernetes 写进业务代码。没有真实容量和运维需求前，先用 Compose 和简单容器编排完成可复现部署。

## 27. 当前代码重构方案

### 27.1 根目录单体

| 当前结构 | 问题 | V2 调整 | 原因 |
| --- | --- | --- | --- |
| web/InventoryController 直接注入 SkuStockMapper | Controller 绕过业务边界 | 改为调用 InventoryReservationService.query | 统一库存入口和异常语义 |
| Controller 内大量手写参数 if | 校验重复、难维护 | 引入 Jakarta Validation；业务不变量仍由 Service 校验 | 区分协议校验与业务校验 |
| StockConcurrencyTest 对照实现和不安全写法位于生产包 | 教学代码容易被复用 | 将不安全实现收敛到测试 fixture 或 src/test | 防止实验路径进入线上 |
| 单体 order、inventory、payment 与微服务重复实现 | 两条路径容易混用 | 单体标注为 regression baseline，消费者路径只走微服务 | 保留实验价值，减少线上歧义 |
| AsyncOrderCommand 与微服务命令表语义相似 | 状态和调度重复 | 抽取事件语义文档和契约；不共享 Entity/Mapper | 只共享概念，不跨库耦合 |
| RedisInventoryService 多层适配 | 适配职责不易理解 | 保留一个 RedisStockGateway，Lua 和账本由 Inventory 拥有 | 简化 Redis 边界 |

### 27.2 微服务

| 当前结构 | 问题 | V2 调整 | 原因 |
| --- | --- | --- | --- |
| gateway/order/inventory/payment 已存在 | 核心履约边界可复用 | 保留并作为主运行路径 | 已有真实测试和补偿语义 |
| fulfillment-api 仅含库存/订单契约 | 商品、购物车契约缺失 | 增加 Commerce 对外 DTO；不放实体 | 让前端有稳定 API |
| payment-service 只有回调调用 | 支付缺少支付单和回调记录 | 增加 Payment repository、状态和审计表 | 贴近真实支付流程 |
| Order 创建已写 order_item | 需要接入商品价格快照 | 扩展请求和快照字段，保持旧字段兼容 | 保障历史订单和改价安全 |
| 服务使用静态 URL | 无服务发现和运行时切换 | V2 先保留环境变量；P2 再接注册/配置中心 | 先完成业务闭环，避免提前引入复杂组件 |
| 本地 Scheduler 负责全部异步工作 | 多实例能力未验证 | P1 统一任务租约，P2 将跨服务事件接入 RabbitMQ | 保持可恢复和可观测 |

### 27.3 新增模块

新增 commerce-service，内部保持模块化结构，不拆成 user/product/cart 三个独立进程：

~~~text
microservices/commerce-service/
└── src/main/java/com/why/fulfillment/commerce
    ├── user/          用户、登录和地址
    ├── product/       SPU、SKU、价格和上下架
    ├── cart/          购物车 Redis 读写与校验
    ├── web/           面向前端的商品和购物车 API
    ├── repository/    Commerce DB Mapper
    └── config/        缓存、认证和 Web 配置
~~~

前端单独放在：

~~~text
frontend/
├── src/pages/        首页、商品、购物车、订单、支付
├── src/api/          Gateway API client
├── src/stores/       用户、购物车、订单状态
└── src/components/   商品卡片、数量选择、状态展示
~~~

前端不直接访问 Order、Inventory 或数据库；所有请求经 Gateway，错误和订单状态以服务端返回为准。

## 28. V1 -> V2 迁移方案

### 阶段 0：冻结现状

- 保留当前单体和微服务测试基线；
- 给现有接口、表和状态建立迁移清单；
- 将 docs/project-function-boundary.md 作为现状基线，不在迁移中删除证据。

### 阶段 1：补商城入口

- 新增 commerce-service 的商品、SKU 和购物车；
- 新增前端商品浏览、购物车和订单入口；
- 商品价格快照和 order_item 接入 Order；
- 不改变 Inventory 的核心状态机。

### 阶段 2：统一订单闭环

- Order 接入 Idempotency-Key 和统一请求摘要；
- Payment 增加支付单与回调记录；
- 统一取消、关单、支付和库存确认状态；
- 前端展示待支付、已支付、已取消和补偿中的订单。

### 阶段 3：生产化可靠性

- 统一 Outbox 事件结构、租约、重试和死信；
- 先在 staging 引入 RabbitMQ，保留数据库 Outbox 作为事实；
- 增加多实例任务测试、服务重启恢复和消息积压测试；
- 增加人工死信查询/重放和受保护的对账修复入口。

### 阶段 4：运行安全与高可用

- 增加用户认证、服务间身份、TLS 和密钥轮换；
- MySQL/Redis/RabbitMQ 按容量需求升级高可用部署；
- 接入追踪、集中日志、告警、备份恢复和灰度发布；
- 使用压测和故障注入形成可复核的容量与恢复证据。

迁移期间，单体不要与微服务共同消费同一批订单命令或 Outbox；切换以订单路由和数据库事实为边界，必要时采用停止写入、迁移、校验、再放流量的方式。

## 29. 开发任务拆分

### P0：核心交易闭环

- [ ] 建立 Commerce DB 和 product_spu/product_sku/cart_item 表；
- [ ] 实现商品查询和购物车 API；
- [ ] 新增前端首页、商品详情、购物车和下单页；
- [ ] Order 接入商品价格快照、订单明细和幂等键；
- [ ] Payment 增加支付单、模拟支付和回调记录；
- [ ] 打通商品 -> 购物车 -> 下单 -> 预占 -> 支付 -> 确认 -> 查询；
- [ ] 补齐重复下单、库存不足、重复支付和超时关单测试。

### P1：高并发与一致性

- [ ] 统一 Inventory 预占/确认/释放契约和状态枚举；
- [ ] 完善 Redis 预扣账本、取消栅栏和对账；
- [ ] 统一 Outbox 事件格式、租约、退避、死信和人工重放；
- [ ] 增加多实例调度、支付/关单竞争和消息重复消费测试；
- [ ] 评估并接入 RabbitMQ，明确每个事件的生产者、消费者和幂等键；
- [ ] 对热点 SKU、Redis 故障、数据库故障和服务重启进行故障注入。

### P2：工程化能力

- [ ] 前端与所有服务容器化并补健康检查；
- [ ] 增加统一认证、服务间身份、TLS 和密钥管理；
- [ ] 接入 trace、集中日志、告警和业务 SLO；
- [ ] 补多实例压测、容量报告、备份恢复和灰度发布流程；
- [ ] 完善 Prometheus/Grafana 看板和死信/对账运维页面。

### P3：扩展业务

- [ ] 优惠券和促销；
- [ ] 搜索和推荐；
- [ ] 商家后台和多商户；
- [ ] 物流、发货、退款和售后；
- [ ] 更复杂的风控、权限和运营系统。

## 30. 后续版本规划

### V2.1：商城可用

完成商品、购物车、订单、支付模拟和前端闭环，确保用户可以从浏览商品走到订单查询。

### V2.2：履约可靠

完成统一 Outbox、RabbitMQ、死信重放、补偿后台和跨进程故障注入，形成稳定的最终一致性链路。

### V2.3：多实例运行

完成服务多副本、任务租约验证、Redis/MySQL 高可用、链路追踪和容量报告。

### V3：业务扩展

在核心交易链路稳定后，再按真实需求增加营销、商家、物流和售后模块。任何新模块都必须先说明数据所有权、状态机、失败补偿和运维边界。

## 31. 设计决策总结

V2 不是把现有实验代码全部推倒重写，也不是为了“生产级”增加大量空服务。正确的演进方式是：

1. 保留当前 Inventory、Order、Payment 的并发和一致性核心；
2. 增加商品、购物车、用户入口和前端，让履约链路成为真实电商业务；
3. 用 commerce-service 模块化承载低复杂度的商城能力，避免过度微服务化；
4. 把 Outbox、幂等、补偿、对账和状态机作为业务基础设施；
5. 在核心闭环稳定后，再补 Broker、高可用、安全和运维；
6. 任何性能数字都注明单机、单实例、测试规模和响应语义，任何生产能力都以真实验证为准。

这样项目对外表达的是一个围绕真实电商交易构建的高并发平台，而不是一组脱离业务的 Redis、Lua、消息和压测实验。
