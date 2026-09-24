# AGENTS.md

本文件是本仓库后续 Codex、Claude Code 或其他 Coding Agent 的工程开发契约。它适用于仓库根目录及所有子目录；当前没有更深层的 `AGENTS.md` 覆盖规则。

## 1. Project Overview

这是一个 Java 17、Spring Boot 3.3.4、Maven 构建的高并发电商平台。当前代码首先实现了库存与订单履约核心，V2 继续补齐商品、购物车、用户入口和前端商城体验。核心价值是让商品、购物车、订单、库存和支付链路在并发及故障条件下保持正确、可恢复和可观测。

仓库当前有两条必须区别对待的运行路径：

- 根目录单体应用：`src/main/java`，默认端口 `8080`，连接 `fulfillment` schema，覆盖周期 0–6 的完整实验基线，包括 MySQL 下单、库存三态、支付幂等、Redisson 延迟关单、Redis Lua 快速路径、异步命令、限流、对账和业务看板。
- `microservices/` 运行切片：`gateway`（`18080`）、`order-service`（`18081`）、`inventory-service`（`18082`）、`payment-service`（`18083`）及只放 DTO/Feign 契约的 `fulfillment-api`。它验证真实 HTTP 边界、Saga 式状态与补偿、跨进程 Outbox、订单创建幂等、持久化超时关单、Redis 快速路径和数据所有权隔离。
- V2 MVP 路径：Vue 前端经 Gateway 进入 `commerce-service`（用户、商品、购物车和结算模块化单体），再调用现有 Order、Inventory、Payment 核心服务。商品到模拟支付的本机链路已验收；支付页、多实例与生产部署仍未完成，证据见 `docs/mvp-v2-test-evidence-2026-09-24.md`。

代码事实优先级如下：

```text
当前可运行代码与测试 > docs/project-function-boundary.md
> docs 下的验收证据 > README 与项目规划 > 一般工程偏好
```

文档与代码不一致时，按当前代码和测试修正实现与文档；不要把规划中的目标能力写成已完成能力。当前 V2 MVP 是本机五进程运行切片，不应描述为生产级高可用系统。

`DESIGN_V2.md` 是 V2 的目标设计和迁移顺序，不是当前实现清单。实现任务必须先判断目标是否已落地，再决定是修复现状、补齐 P0，还是推进 P1/P2。

当前没有 Agent、RAG、LLM、向量库或 Tool Calling 子系统。除非任务明确新增这些产品能力，不要创建对应目录、抽象或调用链。V2 的商城能力应优先落在 `commerce-service` 和 `frontend`，不要被误建成 Agent/RAG 能力。

## 2. System Architecture

### 2.1 单体路径

```text
HTTP Controller
    -> Order / Inventory / Payment Service
        -> MyBatis Mapper + MySQL fulfillment
        -> Redis / Redisson（快速预扣、令牌桶、延迟队列）
        -> Scheduled Task（关单、Outbox、异步命令、对账）
        -> Micrometer / Actuator / Prometheus
```

订单创建与 MySQL 库存预占在同一本地事务中提交或回滚。支付状态和 `PAYMENT_CONFIRMED` Outbox 事件在同一事务中落库，之后由发布器至少一次调用库存确认。Redis 快速路径先完成 Lua 预扣，再把完整命令持久化；HTTP `202` 只表示命令已可靠受理，不表示订单已经同步落库。Redis 与 MySQL 之间没有分布式事务，对账只报告差异，不自动改数。

### 2.2 微服务路径

```text
Client
  -> Gateway
      -> Order Service -> Inventory Service
      -> Payment Service -> Order Service

Order Service  -> fulfillment_order
Inventory      -> fulfillment_inventory + Redis
```

服务间通过 `fulfillment-api` 中的 Feign 接口和 DTO 通信。Order 与 Inventory 各自提交本地事务，不能把 `@Transactional` 解释成跨服务事务。远程结果未知时，订单进入显式状态并由补偿任务重试；库存使用唯一键、条件状态更新和取消栅栏处理重复请求、释放早于预占及晚到预扣。支付服务只负责校验回调签名并调用 Order。

两个业务 schema 当前仍位于同一个 MySQL 实例；最小权限账号已限制服务只能访问自己的 schema。服务地址由环境变量提供的固定 URL 配置，尚未接入 Nacos、负载均衡或多实例验证。

### 2.3 V2 目标路径

```text
Vue 3 前端 -> Nginx / Gateway
                    -> commerce-service（用户、商品、购物车）
                    -> order-service
                    -> payment-service
                    -> inventory-service
```

V2 不继续把用户、商品和购物车拆成三个独立进程。它们先在 `commerce-service` 内按模块组织；Order、Inventory、Payment 保留独立服务，因为它们拥有独立的订单状态、库存并发和支付回调边界。RabbitMQ 属于 P1 可靠性演进，当前仓库仍以数据库 Outbox 和可恢复调度器为事实基础。

## 3. Architecture Layers

本项目是按业务包和服务边界组织的 Spring Boot 应用，不是完整的 Clean Architecture。新增代码应遵守下列实际分层：

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `web` / `controller` / Gateway | HTTP 路由、协议参数绑定、轻量输入校验、状态码和响应转换 | 核心业务规则、事务编排、直接操作数据库或 Redis |
| Application / Service | 用例编排、业务状态迁移、事务边界、调用领域接口和仓储 | HTTP 细节、SQL 字符串、底层 SDK 生命周期 |
| Domain / entity / state | 订单、库存锁定、命令、Outbox 的状态和数据模型；可复用的业务不变量 | Spring Web、MyBatis、Feign、Redis 客户端 |
| Repository / Mapper | MyBatis 查询、条件更新、原子 SQL、持久化映射 | 跨模块流程编排、HTTP 响应 |
| Redis / infrastructure | Redis Lua、Redisson、Feign、数据库连接和外部中间件适配 | 决定订单业务状态或直接生成 API 响应 |
| `task` / scheduler | 定时扫描、租约抢占、重试、退避、死信和补偿触发 | 接收 HTTP 请求、绕过 Service 写业务数据 |
| observability / metrics | Micrometer 指标、Prometheus 暴露、看板快照 | 修改订单或库存业务状态 |

现有根目录 `InventoryController` 的库存查询仍直接依赖 `SkuStockMapper`，这是历史边界。新功能不要复制这种捷径；修改该区域时优先把查询移到 Inventory Service 接口，再由 Controller 调用。

## 4. Dependency Rules

允许的依赖方向：

```text
HTTP / Gateway
    -> Application / Service interface
        -> Domain + Repository interface
            -> Mapper / SQL / Redis / Feign infrastructure
```

具体规则：

1. Controller 只能调用本服务的 Service/Application Service；禁止新代码直接注入 Mapper、`RedisTemplate`、Redisson 或 Feign Client。
2. Service 实现负责事务边界，Mapper 负责持久化动作；Service 不把 `ResponseEntity` 或 Spring Web 类型传入领域层。
3. Entity 是服务内部数据库映射，不能放入 `fulfillment-api`，也不能作为跨服务公开契约。跨服务只使用 DTO、Feign 接口和明确的响应状态。
4. `fulfillment-api` 只能包含服务间 DTO、Feign Client 和必要的内部鉴权配置，不得依赖任何服务的 entity、Mapper、SQL 或数据库连接。
5. Gateway 不访问数据库、不持有订单或库存业务逻辑，只做路由和已有的入口职责。
6. Order 不读取 Inventory 表，Inventory 不读取 Order 私有表。周期 11 后 Inventory 的对账只读取自己的 Redis 预扣账本；不要恢复对 `microservice_order_command` 的跨 schema 依赖。
7. 单体的 `order`、`inventory`、`payment` 包通过接口和事件协作；不要跨包直接访问对方 Mapper、Entity 或实现类。未来需要拆分的调用应先保持接口边界。
8. 禁止在 Domain 层依赖 Spring MVC、Feign、MyBatis 或具体 Redis 客户端。

## 5. Business Boundaries

### Order

Order 负责订单创建、订单状态、订单明细（仅微服务切片当前持久化）、支付状态、超时时间、异步命令和本地 Outbox。普通单体下单的订单与库存预占共享本地事务；微服务下单则使用 `RESERVING -> RESERVED / FAILED / PENDING_COMPENSATION / COMPENSATED` 等显式状态。

Order 不负责直接修改库存表、实现支付签名校验或决定 Redis 具体键值。远程调用超时不能直接当作失败，必须保留可恢复状态并走幂等释放或补偿。

### Inventory

Inventory 负责可售库存 `stock`、锁定库存 `lock_stock`、锁定记录、预占、释放、确认、SKU 查询、Redis 原子预扣和只读对账。预占前必须合并重复 SKU 并按 `skuId` 升序处理，避免反向加锁造成死锁；库存检查和扣减必须使用带条件的原子 SQL 或单次 Lua 执行。

库存锁定记录状态是 `LOCKED -> RELEASED` 或 `LOCKED -> CONFIRMED`。释放和确认必须使用状态条件更新，重复调用应返回幂等结果。Inventory 不负责创建订单、接收外部支付回调或生成自然语言内容。

### Payment

Payment 当前主要接收支付成功回调，校验时间窗和 HMAC-SHA256 签名，并通过内部 Order 契约请求支付状态迁移。V2 增加最小支付单和回调记录，仍由 Payment 拥有支付事实；外部交易号唯一约束和订单条件更新是幂等边界。不要在 Payment 中直接扣库存。

### User、Product、Cart（V2）

V2 的 `commerce-service` 负责用户最小登录/身份、商品 SPU/SKU/价格/上下架和购物车。Product 只拥有商品展示事实，不拥有库存；Cart 保存用户选择，不代表订单价格或库存。提交订单时必须重新读取商品快照并由 Order 调用 Inventory 预占。不要为了这三个模块提前拆出独立服务。

### Async commands、Outbox 与补偿

异步命令和 Outbox 都是持久化事实，不是内存队列。领取必须使用条件更新和租约；租约过期可恢复；失败使用指数退避；达到上限后进入死信。投递语义是至少一次，消费端必须幂等，不得声称 exactly-once。只有明确失败终态才允许 Redis 回补；结果未知或可能已经形成有效订单时继续重试并保留事实。

### Rate limit、reconciliation、observability

单体的双层 Redis Lua 令牌桶只作用于配置的 Redis 下单路径，默认关闭以保护基线压测可比性。对账服务只计算和报告 MySQL、Redis 及未落库预扣之间的差异，不自动修复。指标刷新任务应把数据库读取放在定时线程，不要在 Prometheus scrape 请求线程中查询数据库。

### Agent / RAG

当前项目没有 Agent 或 RAG 业务边界。不要因为任务文本出现“Agent”“Tool”“Workflow”“Embedding”等词就引入新框架；若未来明确新增，必须先给出独立模块边界、状态模型、超时/重试/幂等策略和测试，再实现具体能力。

## 6. Directory Responsibilities

```text
根目录
├── pom.xml                         单体 Maven 构建和依赖版本
├── src/main/java/com/why/fulfillment
│   ├── web/                        单体 HTTP Controller、统一响应和异常处理
│   ├── order/                      订单实体、Service、Mapper、Outbox、异步命令和任务
│   ├── inventory/                  库存实体、原子扣减、Redis、对账和库存 Service
│   ├── payment/                    单体支付回调 Service
│   ├── ratelimit/                  Redis 令牌桶限流
│   └── observability/              指标、运行看板和对账快照
├── src/main/resources
│   ├── mapper/                     MyBatis XML；原子 SQL 和条件更新在这里维护
│   ├── lua/                        单体 Redis Lua 脚本
│   └── application.yml             单体端口、数据源、调度和监控配置
├── src/test/                       单体单元、MockMvc、真实 MySQL/Redis 集成与并发实验
├── sql/                            单体初始化脚本和周期迁移
├── microservices/
│   ├── fulfillment-api/            DTO、Feign Client、内部调用配置；无实体和数据库
│   ├── commerce-service/           V2 用户、商品、SKU、购物车与结算服务
│   ├── gateway/                    Spring Cloud Gateway 路由
│   ├── order-service/              订单编排、状态、Outbox、关单、命令和补偿
│   ├── inventory-service/          库存事务、Redis Lua、账本和对账
│   ├── payment-service/            支付签名校验和 Order 调用
│   ├── sql/                        周期 7–11 微服务迁移和 schema 隔离脚本
│   └── <module>/src/test/           各服务测试（位于对应模块下）
├── frontend/                       V2 Vue 前端商城，经 Gateway 调 API
├── DESIGN_V2.md                    V2 业务边界、架构、迁移和任务设计
├── docs/                           功能边界、规划、审计记录和周期验收证据
├── performance/                    JMeter 计划、同配置压测脚本和口径
├── ops/                            Prometheus 配置、Grafana 数据源和看板
├── docker-compose.yml              MySQL、Redis、Prometheus、Grafana 本地依赖；RabbitMQ 仅在 V2 P1 引入后加入
└── run-tests.ps1                   使用仓库本地 JDK/Maven 的单体测试入口
```

新增文件优先放入已有业务包和模块，不为一层简单转发创建新的目录或抽象。

## 7. Coding Standards

### Java 与类型

- 使用 Java 17；遵循现有 Spring Boot 3 / Jakarta 命名空间和 Maven 父 POM 版本管理。
- 公共方法、Service 接口、Feign DTO 和测试辅助方法写清晰的参数与返回类型；优先使用 `record` 表达不可变请求/响应。
- 金额使用 `BigDecimal` 并匹配数据库 `DECIMAL(12,2)`；数量、订单号和 SKU ID 的范围校验在进入业务层前完成。
- 使用构造器注入；不要新增字段注入或在业务代码里手动创建 Spring 管理的客户端。
- 方法应围绕一个用例或一个状态迁移，避免把调度、序列化、SQL 和业务判断堆进一个 God Service。
- import 分组保持 IDE/项目现有风格；删除未使用 import 和调试输出。

### 校验、事务与并发

- Controller 做协议级校验，Service 再做不可绕过的业务不变量校验；不能只依赖 Controller 保证库存或状态安全。
- 事务注释放在 Service/Application Service 或明确的 Repository 事务方法上。注意同类内部调用不会触发 Spring AOP 代理；需要事务时通过外部 Bean 或重构边界。
- 单体本地事务可覆盖同一 MySQL 中的订单、库存和锁定记录；微服务远程调用绝不能依赖本地事务回滚，必须使用状态机、Outbox、幂等接口和补偿。
- 多 SKU 修改按 `skuId` 升序锁定；不得恢复先查后按绝对值写回的库存逻辑。`sku_stock.version` 是教学对照字段，当前生产路径不使用它。
- 所有重复请求和调度重试都要定义幂等键及状态条件。不要以“返回 false”代替清晰的成功、拒绝、未知或待补偿语义。

### MyBatis、Redis 与重试

- 原子扣减、释放、确认和租约领取使用 SQL 的条件更新，检查受影响行数并区分库存不足、状态冲突和数据库异常。
- Mapper XML 是 SQL 真相；修改表结构和 SQL 时同步检查实体、Mapper 接口、迁移脚本和测试。
- Redis 多键 Lua 逻辑默认面向单实例 Redis。迁移 Redis Cluster 前必须解决 hash tag/分槽问题，不能直接宣称兼容。
- Redis 脚本必须在修改库存前完成全量校验，并用订单号与规范化商品签名实现重复请求保护。补偿和物化必须是明确的幂等状态转换。
- 重试使用已有调度器的租约、退避、最大次数和死信机制；不要在请求线程里无限重试，不要捕获异常后静默继续。

## 8. API Development Rules

Controller 只做参数绑定、协议校验、调用 Service 和 HTTP 响应转换。新 Controller 禁止直接操作 Mapper、Redis、Redisson 或 Feign。

单体公开入口包括：

| 路径 | 语义 |
| --- | --- |
| `POST /api/orders` | MySQL 同步创建待支付订单并预占库存，成功返回 `201` |
| `POST /api/orders/redis` | Redis 原子预扣并持久化异步命令，成功返回 `202` |
| `POST /api/payments/callbacks/success` | 支付成功回调；幂等由订单和外部交易号决定 |
| `GET /api/inventory/skus/{skuId}` | 查询库存 |
| `GET /api/inventory/reconciliation` | 只读库存差异报告 |
| `GET /api/operations/dashboard` | 单体运行看板快照 |

V2 Commerce 对外新增商品和购物车 API 时，经 Gateway 暴露 `GET /api/products`、`GET /api/products/{spuId}`、`GET /api/cart`、`POST/PUT/DELETE /api/cart/items...`。购物车中的价格和库存只是展示数据；提交订单时必须重新校验商品快照并由 Order 调用 Inventory。

微服务公开入口经 Gateway 转发；Inventory 的预占、释放、确认及 Redis 变更仅允许 `/internal/**`，由 `X-Internal-Service-Token` 保护。Payment 回调必须校验 `X-Payment-Timestamp`、`X-Payment-Signature` 和默认五分钟时间窗。

新增或修改 API 时：

1. 先搜索所有 Controller、Feign Client、测试和文档调用方。
2. 保持字段含义、状态码、错误语义和 JSON 兼容；除非任务明确要求 breaking change，不删除或重命名公开字段。
3. 不把 Entity 或数据库内部状态直接暴露成跨服务 DTO；需要新增字段时同步更新契约测试和调用方。
4. 不把异步 `202` 描述为同步业务完成；响应必须说明“已受理”“已完成”“拒绝”“未知”或“待补偿”的实际语义。

## 9. Application / Service Rules

- Service 接口表达业务用例；`impl` 承担流程编排和事务边界。
- 订单 Service 负责订单状态，不直接写库存表；Inventory Service 负责库存状态，不创建订单。
- V2 Commerce Service 负责用户、商品、SKU、购物车和价格快照，不创建订单、不预占库存；提交订单由 Order Service 决定最终订单和库存状态。
- 任务类只负责发现可处理事实、抢占租约和调用 Service；不要把完整业务流程复制到多个 Scheduler。
- 外部调用要设置现有超时：微服务 Feign 默认连接超时 1 秒、读取超时 3 秒。超时后的结果必须按未知处理，不能无条件回补可能已成功的预占。
- Service 异常应携带订单号、SKU、命令号或事件号等必要上下文，但不要把下游地址、密钥或完整请求体返回给客户端。
- 同一个 Service 公开方法的状态迁移应有对应单元测试；跨事务、真实锁竞争、Redis Lua 和 HTTP 语义使用集成测试验证。

## 10. Agent Development Rules

当前没有 Agent Runtime、Agent State、Memory、Tool Registry、Workflow 或 SSE 实现，因此本节默认不适用。若任务明确增加 Agent：

- Agent 只负责决策和编排，不能直接创建数据库、Redis 或模型 SDK 客户端。
- Tool 必须是输入输出明确、职责单一、可独立测试的能力单元；不要把整个订单系统包装成一个 Tool。
- 必须显式定义状态、超时、重试、幂等、取消和错误传播；模型输出不能直接绕过业务 Service 写库。
- streaming/SSE 只能位于接口适配层，核心业务状态必须可持久化和恢复。

## 11. RAG Development Rules

当前没有 Parser、Chunker、Embedding、Vector Store、Retriever、Reranker 或 Context Builder。除非产品需求明确引入 RAG，不要添加向量数据库、LLM SDK 或 RAG Pipeline。若未来引入，应将解析、切分、向量化、存取、召回、重排、上下文构建和模型调用拆成可测试边界，不得把整条 Pipeline 写进 Controller。

## 12. Infrastructure Rules

- MySQL 8 是订单和库存事实存储；Redis 负责快速预扣、令牌桶、延迟队列或微服务预扣账本，不能单独成为订单最终事实。
- Redisson 延迟队列仅用于单体事务提交后的延迟触发；不要在事务提交前入队。微服务关单使用持久化到期扫描，不能混用单体假设。
- OpenFeign 是当前微服务内部 HTTP 适配；契约集中在 `fulfillment-api`，地址、超时和鉴权由配置管理。
- Gateway 使用 Spring Cloud Gateway 路由，不能加入数据库业务逻辑或库存限流以外的跨域编排。
- V2 `commerce-service` 是用户、商品和购物车的模块化业务服务；它可以调用 Inventory 的查询契约，但不能直接写 Inventory 数据库。
- RabbitMQ 当前未纳入基线；V2 P1 只有在 Outbox、事件契约和幂等消费完成后才接入，用于跨服务事件传输、确认、重试和死信。
- Actuator、Micrometer、Prometheus 和 Grafana 负责健康、HTTP histogram、业务积压和差异指标。指标应低基数，不把订单号、用户隐私或原始请求体作为 label。
- Docker Compose 只用于本地 MySQL、Redis、Prometheus 和 Grafana；不要把本地 Compose 验收写成生产部署保证。

## 13. Data & Persistence Rules

### Schema 与迁移

- 根目录单体使用 `fulfillment`；初始化和周期迁移在 `sql/`。
- V2 `commerce-service` 的用户、商品、SKU 和购物车表属于 Commerce 数据所有权；先可与现有实例共存于独立 schema，禁止让 Order/Inventory 直接读取。
- 微服务 Order 使用 `fulfillment_order`，Inventory 使用 `fulfillment_inventory`；周期 7–11 迁移在 `microservices/sql/`，周期 11 的拆 schema 脚本和账号脚本需要一起考虑。
- 表结构变更必须提供可重复执行或明确不可重复的迁移说明，并同步更新实体、Mapper、配置、测试和 README/边界文档。
- 不直接修改已提交历史迁移来“修复”已有环境；新增兼容迁移。执行迁移前后检查旧状态回填、唯一键和索引。
- 数据库账号只能访问自己的 schema。任何跨 schema 读取都必须先证明是现有兼容边界，否则应通过服务接口或事件传递。

### 一致性与状态

- `stock` 是可售库存，`lock_stock` 是预占库存；锁定记录用状态表达预占、释放和确认。
- Outbox、异步命令和 Redis 账本都是可恢复状态机。状态只能按定义方向推进，不能用全量覆盖把已确认/已补偿改回处理中。
- 对账公式和状态过滤必须与当前 `docs/project-function-boundary.md` 及对应证据一致；对账只报告差异，不自动修改库存。
- 不声称分布式事务、exactly-once、零数据丢失、生产高可用或彻底消除死锁。当前证据只覆盖文档注明的单机、单实例和测试规模。

## 14. Error Handling

异常按边界处理：

```text
参数/协议错误       -> IllegalArgumentException 或 ProblemDetail / 400
业务状态拒绝/冲突   -> 明确业务响应、409 或内部状态结果
库存不足/幂等冲突   -> 保留订单号、SKU 和状态上下文，不重复扣减
外部依赖失败       -> UNKNOWN / PENDING_COMPENSATION，进入重试或补偿
数据库/Redis 异常   -> 记录上下文并向调度器或上层传播
```

- 沿用单体 `ApiExceptionHandler` 和微服务各自的异常处理边界，不在 Controller 到处复制状态转换。
- 禁止 `catch (Exception) { return null; }`、`catch (...) { pass; }` 或吞掉异常后返回成功。
- 捕获异常时保留 cause；对外响应只返回安全、稳定、可理解的错误信息，不泄漏 SQL、连接串、服务地址、令牌或堆栈。
- 外部错误和结果未知必须区分。不要因为 HTTP 超时就默认“远端没有成功”。

## 15. Logging & Observability

- 生产代码使用 SLF4J `Logger`；测试中的 `System.out` 仅用于并发/死锁证据输出，不要复制到业务代码。
- 日志至少带上可用的 `orderId`、`skuId`、`commandId`、`eventId`、`leaseOwner` 或服务名；避免打印完整商品 JSON、支付载荷或用户隐私。
- 严禁记录密码、数据库 URL 中的凭证、`INTERNAL_SERVICE_TOKEN`、支付 HMAC 密钥、Authorization Header 或 API Key。
- 重试、死信、积压、对账差异和租约丢失必须使用合适级别：正常完成 `info`，可恢复异常 `warn`，进入死信或数据无法收敛 `error`。
- 新增业务指标前先检查 `FulfillmentMetrics`、`OrderServiceMetrics`、`InventoryServiceMetrics` 的命名和低基数约束；不要把高基数字段做 Prometheus label。

## 16. Configuration & Secrets

敏感值只能来自环境变量或本地未提交配置。禁止硬编码真实密码、令牌、签名密钥或云凭证。当前重要配置包括：

- 单体：`MYSQL_PASSWORD`、`MYSQL_USERNAME`、Redis host/port、调度开关和 `fulfillment.*` 配置。
- 微服务：`ORDER_DATASOURCE_URL`、`ORDER_DB_USERNAME`、`ORDER_DB_PASSWORD`、`INVENTORY_DATASOURCE_URL`、`INVENTORY_DB_USERNAME`、`INVENTORY_DB_PASSWORD`、`INTERNAL_SERVICE_TOKEN`、`PAYMENT_CALLBACK_SECRET`、服务 URL 和端口。
- Compose：`MYSQL_ROOT_PASSWORD`、`GRAFANA_ADMIN_PASSWORD`。

优先在 `application.yml` 使用 `${ENV_VAR:default}`，或通过已有 `@Value` / `@ConfigurationProperties` 注入。业务代码不要散落读取环境变量，也不要把 secret 放入测试日志、README、迁移脚本或提交记录。`.env.example` 只能包含占位值。

## 17. Testing Rules

测试必须验证行为和一致性边界，而不是只追求覆盖率：

- 单元测试：状态迁移、输入校验、重试退避、签名校验、DTO/契约和异常映射。
- Service 测试：订单创建、库存预占/释放/确认、幂等冲突、支付与关单竞争、租约丢失。
- 集成测试：真实 MySQL 原子扣减、InnoDB 死锁对照、真实 Redis Lua、对账和 HTTP 入口。
- 微服务测试：Feign 契约、Gateway 路由、内部令牌、HMAC、四服务状态和补偿；不要只用同进程 Mock 声称跨服务一致性。
- V2 Commerce/前端测试：商品上下架、价格快照、购物车失效、重复提交和从商品到订单的 API/端到端主链路；前端测试不替代后端状态和并发测试。
- 每个失败分支都要验证是否留下可恢复事实、是否错误回补、是否重复扣减或产生错误成功响应。

常用命令：

```powershell
# 根目录单体（使用仓库本地 JDK/Maven）
.\run-tests.ps1

# 传递 Maven 参数，例如单个测试
.\run-tests.ps1 -MavenArgs @('test', '-Dtest=StockConcurrencyTest')

# 微服务父工程；先按 README 配置数据库、Redis 和环境变量
& 'D:\vibecoding\.toolchains\maven\apache-maven-3.9.16\bin\mvn.cmd' `
  '-Dmaven.repo.local=D:\vibecoding\.m2\repository' `
  -f .\microservices\pom.xml test
```

修改核心库存、状态机、异步任务或 SQL 后，至少运行受影响模块测试；修改公共契约、迁移或调度语义后运行对应完整工程测试。周期证据中的测试数量和性能数据是当前基线，不得在未重跑的情况下更新结论。

## 18. New Feature Placement Guide

| 需求 | 首选位置 | 必须同时检查 |
| --- | --- | --- |
| 单体新 HTTP Endpoint | `src/main/java/.../web` | Service 接口、异常处理、MockMvc 测试、README API 表 |
| 单体订单用例 | `order/service` 及 `order/entity` | `OrderMapper.xml`、事务、状态机和任务 |
| 单体库存规则 | `inventory/service` | 锁定记录、原子 SQL、sku 排序、并发/集成测试 |
| 单体 Redis 能力 | `inventory/redis` + `src/main/resources/lua` | 幂等标记、回补、对账和单实例边界 |
| 支付回调规则 | `payment/service` | 外部交易号唯一约束、订单条件更新、回调测试 |
| Outbox/异步任务 | `order/task` + 对应 Mapper/表 | 租约、陈旧恢复、退避、死信和幂等消费 |
| 单体指标或看板 | `observability` | 低基数、刷新线程、Prometheus/Grafana 配置 |
| V2 用户、商品或购物车 | `microservices/commerce-service` 对应模块 | Commerce schema、商品价格快照、认证和前端 API |
| V2 前端页面 | `frontend/src` | Gateway API、登录态、错误状态和端到端流程 |
| 微服务公共请求/响应 | `microservices/fulfillment-api` | 不放 Entity；更新 Feign 契约测试和两端实现 |
| 微服务 Order 用例 | `microservices/order-service/.../service` | Order schema、Repository、状态补偿、任务 |
| 微服务 Inventory 规则 | `microservices/inventory-service/.../service` 或 `redis` | Inventory schema、Lua、账本、取消栅栏、对账 |
| 微服务 Payment 规则 | `microservices/payment-service/.../service` | HMAC 时间窗、Order Feign 和安全错误响应 |
| Gateway 路由 | `microservices/gateway` | 服务 URL 配置和路由测试；不加业务逻辑 |
| 跨服务业务事件 | 先写对应服务 Outbox；V2 P1 再接 RabbitMQ | 事件名称、业务键、投递确认、重试、死信和幂等消费 |
| 数据库变更 | `sql/` 或 `microservices/sql/` | Schema 所有权、迁移可重跑性、Mapper、实体和回归测试 |
| Agent/RAG/LLM | 当前没有默认位置 | 先取得明确需求并单独设计边界，不能塞进现有 Controller/Service |

## 19. Development Workflow

### 修改前

1. 阅读本文件、`DESIGN_V2.md`、`README.md`、`docs/project-function-boundary.md` 和受影响周期的验收证据。明确任务是在当前基线修复，还是在 V2 目标路径新增能力。
2. 从入口开始追踪调用链：Controller/Gateway -> Service -> Mapper/Repository -> MySQL/Redis/Feign -> Scheduler/补偿。
3. 搜索所有接口实现、调用方、Mapper XML、迁移脚本和测试；确认是在单体路径还是微服务路径修改。
4. 识别事务边界、状态迁移、幂等键、补偿路径和外部依赖失败语义。
5. 优先复用已有 Service、状态、脚本、Mapper 和测试工具；新增商品/购物车/用户能力进入 V2 的 `commerce-service`，不要把它们塞入 Order 或 Gateway。

### 修改时

- 遵循最小必要修改原则；只改完成任务所需的模块。
- 保持单体和微服务各自的事实存储、事务和 API 边界；不要用一个路径的实现假设覆盖另一条路径。
- V2 以微服务切片作为商城主路径，根目录单体继续作为回归和并发实验基线；除非任务明确迁移，不要让两条路径共同消费同一批命令或 Outbox。
- 新增核心行为先写能复现边界的测试，再实现修复或功能。并发问题优先使用 CountDownLatch/屏障和真实 MySQL/Redis 验证。
- 修改状态机、SQL、Lua、Feign DTO、环境变量或公开响应时，同步更新相关迁移、契约、证据文档和 README。
- 修改商品、购物车、订单、支付或库存闭环时，同步检查 `DESIGN_V2.md` 中的业务边界、时序和 P0/P1 任务，避免只增加页面或接口而没有失败补偿。
- 不修改无关格式、不重写历史验收记录，不删除能解释设计取舍的测试或日志证据。

### 修改后

至少检查：

- 编译、imports、类型、事务代理和异步线程安全；
- 公开 API、Feign DTO、状态码和配置兼容性；
- MyBatis XML 与 schema/迁移的一致性；
- 异常是否保留上下文，日志和指标是否泄漏敏感数据；
- 重试、租约、死信、补偿和幂等是否覆盖成功、失败、超时、重复和未知结果；
- 受影响测试及必要的完整 Maven 测试；
- `git diff --check`，并确认没有意外修改、密钥或生成文件。

## 20. Compatibility Rules

除非任务明确要求 breaking change，默认保持：

- 单体和微服务公开 HTTP 路径、必要请求字段、成功/错误语义；
- Feign 接口、DTO 字段和内部鉴权头；
- 数据库旧字段、状态值、唯一键语义和可重复迁移行为；
- 环境变量名称及合理默认值；
- 异步命令、Outbox、Redis 账本和订单状态的可恢复性；
- 测试入口和本地 Compose/脚本使用方式。

V2 新增的商品、购物车、用户和前端接口可以作为新增能力演进，但必须保持现有 Order、Inventory、Payment 的请求语义和状态兼容。接入 RabbitMQ 时先保留数据库 Outbox 事实，不得用新 Broker 绕过已有重试、死信和补偿边界。

修改公共接口前必须搜索所有调用方和测试。状态值不能随意复用；数据库字段重命名必须通过兼容迁移。需要改变 `202`、死信、补偿或对账语义时，先更新边界文档并补验收证据。

## 21. Do Not

- 不要绕过 Service 在 Controller、Task 或测试辅助代码中直接写数据库。
- 不要在新代码中恢复先查后扣、绝对值覆盖写或不排序的多 SKU 加锁。
- 不要把单体 `@Transactional` 延伸到 Feign/HTTP 调用。
- 不要让 Order 读取 Inventory 表，或让 Inventory 读取 Order 私有表。
- 不要在 `fulfillment-api` 放实体、Mapper、SQL 或业务实现。
- 不要把 Redis `202`、Outbox 至少一次或死锁实验写成同步完成、exactly-once 或生产保证。
- 不要静默吞掉异常、返回 `null` 表示失败或在不确定结果时盲目补偿。
- 不要硬编码密码、令牌、HMAC 密钥、数据库凭证或服务地址中的敏感信息。
- 不要在业务日志打印完整请求、支付签名、Authorization Header 或用户隐私。
- 不要为了一个小功能引入 Nacos、Seata、RocketMQ、Sentinel、Kubernetes 或新的 ORM；先证明现有依赖无法解决问题。
- RabbitMQ 只按 `DESIGN_V2.md` 的 P1 事件方案引入：先有 Outbox 事实、事件契约、消费幂等和死信设计，再增加 Broker 依赖。
- 不要创建只有一层转发的 Factory、Repository、Abstract Base Class 或巨大 `common` 模块。
- 不要大规模移动目录、重命名文件、替换技术栈或重写无关模块。
- 不要假设 README、规划书或旧周期文档一定比当前代码更新。
- 不要虚构不存在的 Agent、RAG、API、测试、性能指标或生产能力。

## 22. Definition of Done

一项代码修改只有同时满足以下条件才算完成：

- 修改位置符合单体/微服务当前架构和依赖方向；
- 核心逻辑位于正确的 Service、Domain、Mapper、Infrastructure 或 Task 层；
- 输入、输出、状态迁移、幂等键和异常语义明确；
- 事务只覆盖它实际能够保证的本地边界；跨服务未知结果可重试、可补偿、可恢复；
- 原子 SQL、Lua、租约、Outbox、死信和对账没有破坏已有不变量；
- 没有重复实现已有能力，也没有无必要的抽象或依赖；
- 敏感配置来自环境变量，没有泄漏到代码、日志、测试或文档；
- 新增或修改的核心行为有适当的单元、Service、契约或集成测试；
- 受影响的 Maven 测试通过，已有测试未被破坏；
- 对外 API、schema、配置和契约保持兼容，除非任务明确记录 breaking change；
- 代码、README、`AGENTS.md`、`DESIGN_V2.md`、功能边界、迁移和验收证据保持一致；
- 涉及商品、购物车、订单、支付或库存的改动，能够从前端入口追踪到最终状态和失败补偿；
- `git diff --check` 通过，工作区没有无关生成物或意外改动。

完成任务的最终报告应说明：修改了哪些文件和边界、运行了哪些验证、哪些结果只在单机/单实例/限定测试条件下成立，以及仍然存在的明确限制。
