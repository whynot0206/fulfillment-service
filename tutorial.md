# 高并发电商平台：从商城操作到履约一致性的源码课程

## 本课程怎么学

这份课程面向“能看懂 Java / Spring Boot 基础代码，但还不能独立讲清整个项目”的学习者。目标不是背技术栈，而是能顺着一次用户操作，找到入口、关键状态、数据修改、失败分支和验证证据。

- 源码基线：`cac9236c92cf3a0550d047bddf0d6fe19481af9a`（`feat: add owned order cancellation and demo payment`）。
- 初稿日期：2026-09-26；以以上提交为起点。同日可靠性修复后，针对性更新第 04、05、07 课的恢复机制、当前符号与仓库相对链接。
- 当前交付：11 课源码课程大纲，全部标记为“大纲”；不是已展开的逐段讲解，也没有提前生成习题或答案。
- 使用方式：按课内编号阅读，不要从目录第一行一路往下翻。后续可以指定“展开第 04 课”，再为已经讲过的课程生成练习。
- 验证方式：课程初稿来自源码与历史记录；后续实际构建、故障与浏览器结果见 [可靠性专项](docs/reliability-recovery-2026-09-26.md)。文中的历史单体压测不代表新版商城复验。

前置基础只需要：Java 类与接口、集合和异常；Spring 的依赖注入、Controller 和事务代理；HTTP 请求/响应；SQL 的查询、更新、唯一键和事务；Redis 基本键值。Vue 只需看懂“按钮调用哪个函数、函数请求哪个接口”，不要求先学完整前端框架。

### 先分清两条路径

| 路径 | 这次如何学习 | 不能混用的事实 |
| --- | --- | --- |
| `frontend/` + `microservices/` | 商城主线：用户、商品、购物车、结算、订单、库存和本地模拟支付 | 跨服务不共享一个数据库事务；超时关单使用数据库扫描 |
| 根目录 `src/` | 并发实验与历史方案对照 | 单体本地事务、Redisson 延迟关单和压测数字不能直接套到新版商城 |

新版主链的缩略地图：

```text
浏览器 → Gateway
           ├─ Commerce：用户、商品、购物车、结算
           │      └─ HTTP → Order：订单与明细
           │                    └─ HTTP → Inventory：库存预占
           ├─ Order：本人订单查询 / 取消
           └─ Payment：本地模拟支付 / 验签回调
                         └─ HTTP → Order：支付状态 + Outbox 本地事务
                                         └─ 定时任务 → HTTP → Inventory：确认库存
```

这里的 HTTP 调用是跨进程边界；“定时任务”是后续独立触发，不是支付请求继续同步执行。当前没有消息 Broker 参与这条链路。

### 覆盖范围与暂不覆盖范围

本次覆盖消费者主链、身份边界、商品与订单快照、幂等、库存并发、支付/取消竞争、Outbox、Redis 异步入口、库存账本、只读对账、指标和压测口径。

暂不逐文件展开：CSS 与页面布局、每个 DTO/异常类、每个历史迁移、全部单体重复实现、所有测试辅助代码。它们在所属课程按需补读，不作为独立课程。

不存在或尚未验收的能力不作为已实现内容教学：真实支付渠道、持久化支付单、物流退款、RabbitMQ、Nacos、多实例高可用、生产容量和自动对账修复。当前是本机五进程运行切片，三个业务 schema 仍共用 MySQL 实例。

阅读时以方法体、SQL、Lua 和测试断言为准。[DESIGN_V2.md](DESIGN_V2.md) 是目标设计；旧注释和手册也可能落后于代码。特别注意：

- 商城结算调用普通 Order 创建接口，并没有默认切换到 Redis 异步下单。
- Commerce 的结算记录、Order 的订单创建幂等、Inventory 的库存幂等不是同一层。
- 不要笼统说“所有任务都有租约、退避和死信”：普通补偿、支付 Outbox、Redis 命令三种实现不同。
- “请求被受理”“订单已创建”“库存已确认”是三个不同的完成点。

## 课程地图

| 课号 | 要回答的业务问题 | 前置 |
| --- | --- | --- |
| 01 | 前端和五个后端进程怎样连起来？ | 基础 |
| 02 | 后端怎样确认“这是谁的操作”？ | 01 |
| 03 | 商品加入购物车后，究竟存了什么？ | 02 |
| 04 | 点击结算后，怎样校验价格并防止重复提交？ | 03 |
| 05 | Order 怎样跨服务创建订单并处理未知结果？ | 04 |
| 06 | 多人抢同一批 SKU，为什么不会重复卖出？ | 05 |
| 07 | 支付响应成功后，库存为什么还可能在锁定？ | 05、06 |
| 08 | 用户取消、超时关单和支付竞争如何处理？ | 07 |
| 09 | Redis 异步下单被受理后，怎样走到持久化结果？ | 05—08 |
| 10 | 怎样发现库存差异和异步积压？ | 09 |
| 11 | 怎样证明功能正确，并正确解释压测结果？ | 06—10 |

首次学习建议先读 01—08，能复述普通交易闭环后再进入 09—11。下面的“读完能回答”是课程学习目标，不是已配好答案的面试题库。

## 第 01 课：把前端和后端连成一张运行地图

状态：大纲。

读完能回答：为什么打开前端页面，不等于只启动了一个 Spring Boot？`fulfillment-api` 为什么不是一个运行服务？

主链：启动应用 → Vue 装配路由、Spring 装配各服务 → 前端请求 `/api` → Vite/Nginx 代理到 Gateway → 路由到业务服务 → 返回页面数据；缺少必要配置或依赖不可用时不能把页面加载成功当成后端链路就绪。

必读顺序：

1. [microservices/pom.xml](microservices/pom.xml) 的 `modules`。输入是微服务构建入口；先识别五个运行模块和一个契约模块，下一步寻找各自启动类；输出是“模块 ≠ 都要启动的进程”。
2. [CommerceServiceApplication.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/CommerceServiceApplication.java) 的 `main`、`@MapperScan`、`@EnableFeignClients`，以及 [OrderServiceApplication.java](microservices/order-service/src/main/java/com/why/fulfillment/order/OrderServiceApplication.java) 的 `@EnableScheduling`。输入是 Spring 启动；下一步由容器注册 Mapper、Feign 和任务；输出是后续调用链的装配依据。
3. [frontend/src/main.js](frontend/src/main.js) 的 `restoreSession`、`setUnauthorizedHandler` 和 `createApp`，再到 [router/index.js](frontend/src/router/index.js) 的 `routes`、`beforeEach`。输入是浏览器加载；下一步选择页面并恢复登录态；输出是页面入口，而不是后端权限结论。
4. [http.js](frontend/src/api/http.js) 的 `request` → [vite.config.js](frontend/vite.config.js) 的 `server.proxy` → [Gateway application.yml](microservices/gateway/src/main/resources/application.yml) 的 `spring.cloud.gateway.routes`。输入是相对地址；下一步由代理与路由选择服务；输出是实际 HTTP 目的地和响应/错误。
5. [commerce application.yml](microservices/commerce-service/src/main/resources/application.yml)、[order application.yml](microservices/order-service/src/main/resources/application.yml)、[inventory application.yml](microservices/inventory-service/src/main/resources/application.yml) 的数据源与服务 URL。输入是配置/环境变量；下一步连接各自资源；输出是数据归属，不能因同一个 MySQL 实例就认为可以跨服务查表。

选读验证：[GatewayRoutesTest.java](microservices/gateway/src/test/java/com/why/fulfillment/gateway/GatewayRoutesTest.java)、[V2.1 验收记录](docs/v2-order-actions-evidence-2026-09-24.md)。前者检查路由配置，后者记录历史五进程 HTTP 结果；都不能证明现在本机服务正在运行。

暂缓：JWT 验签交给第 02 课；商品 SQL 交给第 03 课；定时任务执行细节交给第 07—10 课。

## 第 02 课：登录以后，后端怎样知道你是谁

状态：大纲。

读完能回答：JWT、`X-User-Id` 和内部服务令牌分别负责什么？为什么前端路由守卫不能代替后端鉴权？

主链：注册/登录 → Commerce 校验账号并签发 JWT → 浏览器在后续请求带 Bearer Token → Gateway 剥离可伪造身份头并验签 → 下游重新验签或校验内部令牌 → 基于当前用户处理；无效身份返回 401，而不是相信客户端填写的用户编号。

必读顺序：

1. [AuthController.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/auth/web/AuthController.java) → [AuthService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/auth/service/AuthService.java) 的 `register`、`login`、`issue`。输入是注册/登录请求；下一步查询账号、编码或比对密码；输出是登录结果和令牌。
2. [JwtCodec.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/auth/JwtCodec.java) 与 [PasswordEncoderConfiguration.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/auth/PasswordEncoderConfiguration.java)。输入是账号身份与配置；下一步执行签发/验证及密码编码；输出是令牌声明或拒绝，不需要先逐行研究加密算法。
3. [session.js](frontend/src/stores/session.js) 的 `restoreSession`、`currentToken`、`signOut` → [http.js](frontend/src/api/http.js) 的 `request`。输入是已有登录状态；下一步加 Authorization 头、处理 401；输出是前端状态变化。
4. [AuthenticationGlobalFilter.java](microservices/gateway/src/main/java/com/why/fulfillment/gateway/auth/AuthenticationGlobalFilter.java) 的 `filter` → [JwtTokenVerifier.java](microservices/gateway/src/main/java/com/why/fulfillment/gateway/auth/JwtTokenVerifier.java) 的 `verifyAndExtractUserId`。输入是不可信请求；下一步先删除伪造头，再按白名单/验签分支处理；输出是由网关计算的用户 ID。
5. [WebMvcConfiguration.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/common/WebMvcConfiguration.java) 的参数解析器注册 → [CurrentUserArgumentResolver.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/auth/CurrentUserArgumentResolver.java) 的 `supportsParameter`、`resolveArgument`。输入是带 `@CurrentUser` 的参数；下一步 Commerce 自己校验 JWT；输出是 `JwtPrincipal`，并非盲信用户头。
6. [Order InternalServiceTokenFilter.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/InternalServiceTokenFilter.java) 与 [PaymentMockTokenFilter.java](microservices/payment-service/src/main/java/com/why/fulfillment/payment/web/PaymentMockTokenFilter.java)。输入是到达业务端口的请求；结合第 01 课网关重新注入令牌的配置，追踪放行/拒绝；输出是直连伪造用户头为何不能直接操作订单/模拟支付的边界。

选读验证：[JwtCodecTest.java](microservices/commerce-service/src/test/java/com/why/fulfillment/commerce/auth/JwtCodecTest.java)、[AuthenticationGlobalFilterTest.java](microservices/gateway/src/test/java/com/why/fulfillment/gateway/auth/AuthenticationGlobalFilterTest.java)、[InternalServiceTokenFilterTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/web/InternalServiceTokenFilterTest.java)。

边界：已有 JWT 和共享内部令牌不等于生产网络隔离、TLS、密钥轮换或完整权限体系。`/api/auth/**` 在网关白名单里，也不代表 Commerce 的 `/me` 不需要身份。

暂缓：订单归属查询在第 05 课；支付回调的 HMAC 在第 07 课，它不是用户 JWT。

## 第 03 课：从商品详情到购物车，分清“展示”和“事实”

状态：大纲。

读完能回答：商品价格、可售库存和购物车数量分别由谁拥有？加入购物车为什么不代表买到了库存？

主链：浏览商品/加入购物车 → Product/Cart API → 读取商品表、按需查询 Inventory、保存购物车选择 → 组装展示结果；商品不可用或库存查询未知时标记对应状态，而不是把查询当成预占。

必读顺序：

1. [ProductDetailView.vue](frontend/src/views/ProductDetailView.vue)、[CartView.vue](frontend/src/views/CartView.vue) → [api/index.js](frontend/src/api/index.js)。输入是浏览/加购/修改数量；下一步找到实际 URL、参数和响应字段；输出是浏览器与后端之间的数据契约。
2. [ProductController.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/product/web/ProductController.java) → [ProductQueryService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/product/service/ProductQueryService.java) 的 `listProducts`、`getDetail`。输入是分页或 SPU；下一步查询商品、SKU 和图片；输出是列表或详情。列表并不是逐商品查询实时库存。
3. [SkuAvailabilityService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/product/service/SkuAvailabilityService.java) → [InventoryClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/inventory/InventoryClient.java)。输入是 SKU；下一步通过 HTTP 查库存；输出是数量或未知。批量包装不等于存在一个真正的远程批量接口。
4. [CartController.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/cart/web/CartController.java) → [CartService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/cart/service/CartService.java) 的 `add`、`updateQuantity`、`view`、`assemble`。输入是当前用户及 SKU/数量；下一步写购物车并重新组装展示；输出是用户选择，不是订单或库存锁。
5. [CartItemMapper.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/cart/mapper/CartItemMapper.java) 与 [CartCache.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/cart/service/CartCache.java)。输入是购物车读写；下一步检查数据库事实、缓存命中/回源和事务完成后的失效；输出是缓存与持久化的真实分工。注意实现使用 `afterCompletion`，不要误读成“仅提交后清缓存”。

选读验证：[migration-v2-commerce.sql](microservices/sql/migration-v2-commerce.sql) 的商品、购物车表；[MVP 验收记录](docs/mvp-v2-test-evidence-2026-09-24.md) 的商品与购物车 HTTP 证据。当前不能用 Checkout 的 Mock 测试替代购物车缓存的真实并发验收。

边界：购物车展示金额会随商品查询变化；历史订单快照由第 04—05 课处理。库存查询失败的未知值不能当成有货，也不能无条件当成零库存。

暂缓：点结算后的重新校验、幂等及清理购物车交给第 04 课；真正预占交给第 05—06 课。

## 第 04 课：一次结算如何防止改价误下单和重复提交

状态：大纲。

读完能回答：为什么不能相信浏览器传来的价格？一次操作重试为什么要复用幂等键？后端超时后怎样避免再建一张订单？

主链：点击结算 → `POST /api/checkout` → 优先查找用户与幂等键对应的原意图；已有意图核对原金额并重放，新键才读取选中项与商品、比对金额并持久化快照 → 调用 Order；成功记录订单并条件清理购物车，明确拒绝记录拒绝，未知结果保留处理中。

必读顺序：

1. [CartView.vue](frontend/src/views/CartView.vue) 的 `checkout`、`handleCheckoutError` → [checkout.js](frontend/src/api/checkout.js) 的 `currentCheckoutKey`、`rotateCheckoutKey`。输入是用户操作；下一步持有并发送幂等键；输出是可重试的一次结算。以组件分支为准：成功或明确拒绝可换键，未知结果不能自动当作新操作。
2. [CheckoutController.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/checkout/web/CheckoutController.java) → [CheckoutService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/checkout/service/CheckoutService.java) 的 `submit`。输入是当前用户、`Idempotency-Key`、预期金额；下一步先查原意图并核对原金额，只有新键才读取购物车；输出是结算结果或明确错误，清车后的同键重放也不依赖当前购物车。
3. 同一 `CheckoutService` 的 `revalidate`、`sum`、`requireAmountUnchanged`、`digest`。输入是选中项；下一步重新查询上下架、价格和库存可用性并规范化摘要；输出是服务端计算的商品快照与总额，不是采用客户端报价。
4. 同一类的 `claimIdempotencyKey` → [CheckoutRequestMapper.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/checkout/mapper/CheckoutRequestMapper.java) 的 `insertClaim`、`selectByUserAndKey`。输入是包含原订单号、快照、摘要和金额的意图；下一步依赖数据库唯一约束认领；并发冲突后读取获胜意图，相同金额重放、异金额拒绝。摘要已持久化，但当前不以后来购物车的摘要重新判定原意图。
5. `CheckoutService.toOrderRequest` → [OrderClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/order/OrderClient.java) 的 `create`。输入是订单号和商品快照；下一步跨 HTTP 进入 Order；输出是 `RESERVED`、拒绝或不确定结果。本课在远程边界暂停，内部实现交给第 05 课。
6. 回到 `submit`、`clearCheckedOutItems`，继续读 [CheckoutRecoveryService.java](microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/checkout/service/CheckoutRecoveryService.java) 的 `prepare / executeInitial / replay / recover` 和 Mapper 的 `finishSubmitted / finishRejected / markCartCleaned`。先持久化原订单号、快照和租约，再调用远端；同步成功只按购物车原行 ID 与 revision 清理，恢复路径不清车。

选读验证：[CheckoutServiceTest.java](microservices/commerce-service/src/test/java/com/why/fulfillment/commerce/checkout/service/CheckoutServiceTest.java)，优先找金额变更、库存未知、摘要规范化、同键异金额拒绝、购物车改变后仍重放原意图、成功后空购物车重放、下游异常等断言。其旧注释可能仍称方法“留空”，但当前关键方法已有实现，不要按旧注释当作填空练习。

边界：2026-09-26 已新增 `CheckoutRecoveryTask`。处理中记录通过原快照、租约与有限重试恢复；创建窗口截止后仅调用 `resolve-create` 核对，仍未知则转人工确认。相同键先读原意图及原金额，不按当前购物车重建；不能“不断换键直到成功”。整个结算没有覆盖 Commerce 与 Order 的本地事务。

暂缓：Order 的业务载荷幂等与预占状态交给第 05 课；Lua 幂等是第 09 课的另一层。

## 第 05 课：Order 怎样跨服务创建订单

状态：大纲。

读完能回答：订单先落库还是库存先扣？HTTP 超时为什么不一定代表库存没有扣？同一个订单号重放怎样判断是不是同一笔业务？

主链：Commerce 内部创建请求 → Order 校验快照与金额 → 本地事务保存订单和明细 → HTTP 预占库存 → 持久化预占结果；成功、明确拒绝、未知补偿各走不同分支，随后用户通过本人订单查询看到结果。

必读顺序：

1. [OrderClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/order/OrderClient.java) 的 `create` → [InternalOrderController.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/InternalOrderController.java) → [OrderApplicationService.java](microservices/order-service/src/main/java/com/why/fulfillment/order/service/OrderApplicationService.java) 的 `createFromCommerce`。输入是完整快照；下一步校验总额等于明细金额之和；输出为统一创建命令。
2. `OrderApplicationService.createPending`、`normalize`、`samePayload`、`resultForExisting`。输入是创建命令；下一步创建或读取同订单号事实；输出是继续预占、同载荷重放或冲突。当前 Order 拒绝重复 SKU，Inventory 的合并规则是更深一层的保护。
3. [OrderRepository.java](microservices/order-service/src/main/java/com/why/fulfillment/order/repository/OrderRepository.java) 的 `insertPending`。输入是订单和明细；下一步在本地事务写两张表；输出是 `PENDING_PAYMENT + RESERVING`。此事务结束后才继续远程预占，不包含 Inventory 的提交。
4. `createPending` → [InventoryClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/inventory/InventoryClient.java) 的 `reserve` → [InventoryController.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/web/InventoryController.java) 的 `reserve`。输入是订单号与 SKU 数量；下一步由 Inventory 执行自己的事务；输出是远端预占结果。库存内部下一课再深挖。
5. 回到 `createPending`、`compensateUnknown` 和 Repository 的 `updateReservation`、`markCompensationPending`。输入是成功、拒绝、异常或空响应；下一步更新状态或尝试幂等释放；输出是 `RESERVED / FAILED / COMPENSATED / PENDING_COMPENSATION`。条件更新未命中时要看并发后的持久化状态，不能一律释放库存。
6. [OrderController.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/OrderController.java) → `OrderApplicationService.findOwned`、`findForUser` → Repository 查询。输入是当前用户与订单号/分页；下一步检查归属并读取快照；输出是本人订单，别人订单不能因知道 ID 就读到。

选读验证：[OrderCreateFromCommerceTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/service/OrderCreateFromCommerceTest.java)、[OrderApplicationServiceTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/service/OrderApplicationServiceTest.java)、[FeignContractTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/FeignContractTest.java)。重点检查金额不一致、重放、远端结果未知、并发创建已成功却误补偿的保护。

边界：订单主状态看 [OrderStatus.java](microservices/order-service/src/main/java/com/why/fulfillment/order/domain/OrderStatus.java)，预占状态看 [ReservationStatus.java](microservices/order-service/src/main/java/com/why/fulfillment/order/domain/ReservationStatus.java)，不要套规划中的枚举。新增 `OrderReservationRecoveryScheduler` 扫描超过宽限的普通 `RESERVING`，先赢得取消 CAS，再释放库存；必须排除由 Redis 命令拥有的订单。晚到请求未赢取消 CAS 时不能释放已经有效的预占。

暂缓：库存内部原子性下一课讲；取消与补偿任务第 08 课讲；Redis 命令恢复第 09 课讲。

## 第 06 课：库存正确性——先解决超卖，再理解多 SKU 死锁

状态：大纲。

读完能回答：为什么加了事务的“先查后扣”仍有问题？原子 SQL、订单库存锁记录和统一锁顺序分别解决什么？

主链：多个订单竞争库存 → Inventory 规范化 SKU → 在本地事务内按统一顺序处理库存记录 → 带余量条件的 SQL 扣可售、加锁定 → 全部成功才提交；不足或异常回滚。再用单体对照实验观察不安全写法与反向加锁。

必读顺序：

1. [InventoryReservationServiceImpl.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/service/impl/InventoryReservationServiceImpl.java) 的 `reserve`。输入是订单号、SKU 和数量；下一步校验/合并/排序并进入本地事务；输出是统一的库存处理顺序。
2. [InventoryReservationFenceMapper.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/mapper/InventoryReservationFenceMapper.java) 与 [SkuStockLockMapper.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/mapper/SkuStockLockMapper.java)。输入是订单标识；下一步检查取消栅栏和已有预占；输出是可执行、同载荷重放或冲突。取消先到的含义留到第 08 课展开。
3. [微服务 SkuStockMapper.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/mapper/SkuStockMapper.java) 的 `reserve`、`release`、`confirm`。输入是 SKU 和数量；下一步检查 SQL 条件及影响行数；输出分别为“可售转锁定”“锁定退回可售”“消耗锁定”。确认付款不是再扣一次可售库存。
4. 切到实验基线：[StockServiceImpl.java](src/main/java/com/why/fulfillment/inventory/service/impl/StockServiceImpl.java) 的 `deductNaive`、`deductNaiveInTransaction`、`deductAtomic` → [单体 SkuStockMapper.xml](src/main/resources/mapper/SkuStockMapper.xml)。输入是同一扣减需求；下一步比较绝对值覆盖与条件增量更新；输出是可以被并发实验区分的三种行为。这里已经换了运行路径。
5. [StockConcurrencyTest.java](src/test/java/com/why/fulfillment/inventory/StockConcurrencyTest.java) 的 `race`、`naive_shouldOversell`、`naiveInTransaction_shouldOversell`、`atomic_shouldNotOversell`。输入是同时竞争的任务；下一步统计成功、拒绝、异常与真实库存变化；输出是正确性断言。库存没有变成负数，不代表没有超卖。
6. [Cycle4DeadlockIntegrationTest.java](src/test/java/com/why/fulfillment/inventory/Cycle4DeadlockIntegrationTest.java) 的 `runUnorderedTransaction`、`reverseLockOrderShouldReproduceDeadlock`、`reverseInputOrderShouldCompleteWithoutDeadlock`。输入是相反顺序的两个 SKU；下一步用屏障制造持锁等待，再对比排序实现；输出是此实验条件下的死锁回滚和完整提交数。

选读验证：[InventoryReservationServiceImplTest.java](microservices/inventory-service/src/test/java/com/why/fulfillment/inventory/InventoryReservationServiceImplTest.java)、[周期 4 验收记录](docs/cycle4-evidence-2026-09-19.md)。

边界：排序降低这类反向锁顺序的死锁风险，不是消除所有数据库死锁。300 个并发任务也不等于 300 个同时持有数据库连接。上述真实数据库实验会重置 SKU 和清理指定测试记录，必须使用可重置的专用测试库，不能直接运行在演示交易数据或生产数据上。

暂缓：支付确认与释放的业务触发分别由第 07、08 课负责；Redis Lua 的原子性在第 09 课对照。


## 第 07 课：支付成功为什么还需要 Outbox

状态：大纲。

读完能回答：为什么不在支付回调里直接把订单、库存一次改完？重复回调与重复库存确认分别怎样处理？

主链分成两个完成点：

```text
请求内：
模拟支付 / 已验签回调 → Payment → HTTP → Order
→ 本地事务：订单 PAID + PAYMENT_CONFIRMED 事件 → 返回支付处理结果

后续定时触发：
InventoryConfirmationPublisher → 条件领取 Outbox → HTTP → Inventory.confirm
→ 库存锁记录确认 → 标记事件已发送；失败安排重试/死信
```

必读顺序：

1. [OrderDetailView.vue](frontend/src/views/OrderDetailView.vue) 的 `pay` → [PaymentMockController.java](microservices/payment-service/src/main/java/com/why/fulfillment/payment/web/PaymentMockController.java) 的 `success` → [PaymentApplicationService.java](microservices/payment-service/src/main/java/com/why/fulfillment/payment/service/PaymentApplicationService.java) 的 `mockSuccess`。输入是当前用户及订单；下一步通过 Order 内部查询核对归属、生成稳定模拟交易号；输出为复用支付确认路径的请求，不产生真实扣款。
2. 另一入口：[PaymentCallbackController.java](microservices/payment-service/src/main/java/com/why/fulfillment/payment/web/PaymentCallbackController.java) 的 `success` → [PaymentCallbackSignatureVerifier.java](microservices/payment-service/src/main/java/com/why/fulfillment/payment/service/PaymentCallbackSignatureVerifier.java) 的 `verify`。输入是订单号、交易号、时间戳和签名；下一步检查 HMAC 与时间窗；输出是允许处理或拒绝。这不是第 02 课的用户登录鉴权。
3. `PaymentApplicationService.acceptSuccess` → [OrderClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/order/OrderClient.java) 的 `markPaid` → [InternalOrderController.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/InternalOrderController.java) 的 `markPaid`。输入是支付确认；下一步跨进程调用 Order；输出为接受、拒绝或未知，不是库存确认完成。
4. [OrderApplicationService.java](microservices/order-service/src/main/java/com/why/fulfillment/order/service/OrderApplicationService.java) 的 `markPaid` → [OrderRepository.java](microservices/order-service/src/main/java/com/why/fulfillment/order/repository/OrderRepository.java) 的 `markPaidIfPending`。输入是交易号；下一步条件更新待支付且已预占订单，并在同一事务写事件；输出是两项本地事实一起提交。未命中时读取既有状态，只有同订单、已支付、同交易号才算幂等成功。
5. [InventoryConfirmationPublisher.java](microservices/order-service/src/main/java/com/why/fulfillment/order/task/InventoryConfirmationPublisher.java) 的 `publishReady`、`publish` → `ConfirmationOutboxRepository` 的 `recoverStaleConfirmationClaims`、`claimConfirmationEvent`。输入是定时器触发，不是原支付请求；下一步恢复陈旧领取并条件认领；输出是待调用 Inventory 的订单号。
6. [InventoryClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/inventory/InventoryClient.java) 的 `confirm` → [InventoryController.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/web/InventoryController.java) 的 `confirm` → [InventoryReservationServiceImpl.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/service/impl/InventoryReservationServiceImpl.java) 的 `confirm`。输入是订单号；下一步将仍锁定的记录确认并消耗锁定库存；输出是可重复调用的结果。
7. 回到 Repository 的 `markConfirmationSent`、`retryConfirmation`。输入是消费结果；下一步记录已发、退避重试或死信；输出是下次调度依据。必须思考“库存已确认，但已发标记还没写入”的重放窗口。

选读验证：[PaymentApplicationServiceTest.java](microservices/payment-service/src/test/java/com/why/fulfillment/payment/service/PaymentApplicationServiceTest.java) 的 `mockPaymentRequiresOrderOwnership`、`mockPaymentUsesStableTradeNumberForRetries`；[InventoryConfirmationPublisherTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/task/InventoryConfirmationPublisherTest.java) 的 `confirmedEventIsMarkedSent`、`failedConfirmationReturnsEventToRetryQueue`；[签名验证测试](microservices/payment-service/src/test/java/com/why/fulfillment/payment/service/PaymentCallbackSignatureVerifierTest.java)。

边界：这是至少一次调用，库存消费需要幂等。投递状态现位于 `ConfirmationOutboxRepository`：每次领取独立 owner、60 秒租约，写回校验 owner 与未过期租约，第 10 次失败转死信；受控重驱需核对已支付事实并留审计。源码与单实例故障测试不能代替多实例竞争验证。Payment 还没有持久化支付单/回调记录，重新排队也不等于库存已确认。

暂缓：与关单抢同一订单状态的分支下一课讲；积压指标第 10 课讲。

## 第 08 课：取消、超时和支付，谁有权释放库存

状态：大纲。

读完能回答：支付和取消同时到达，以谁为准？补偿请求先于预占到达，会不会又被晚到请求扣掉库存？

主链：

```text
主动取消 → 校验归属 ───────────────┐
到期扫描 → 找到已到期且已预占订单 ─┤
                                  ↓
                   条件更新订单为取消 + 待补偿
                   ├─ 未命中：可能支付/其他操作已赢，不随意释放
                   └─ 命中：调用 Inventory.release
                              ├─ 成功：记录已补偿
                              └─ 失败：保留待补偿，后续定时扫描重试
```

必读顺序：

1. [OrderDetailView.vue](frontend/src/views/OrderDetailView.vue) 的 `cancel` → [OrderController.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/OrderController.java) 的 `cancel` → [OrderApplicationService.java](microservices/order-service/src/main/java/com/why/fulfillment/order/service/OrderApplicationService.java) 的 `cancelOwned`。输入是用户与订单；下一步检查归属和现有状态；输出为可取消、重复取消、冲突或找不到。
2. [OrderRepository.java](microservices/order-service/src/main/java/com/why/fulfillment/order/repository/OrderRepository.java) 的 `markUserCanceledForCompensation`，对照第 07 课 `markPaidIfPending` 的 WHERE 条件。输入是竞争中的同一订单；下一步由条件更新决定谁推进状态；输出是影响行数，不是按哪个请求先到 Controller 来判胜负。
3. [OrderExpirationScheduler.java](microservices/order-service/src/main/java/com/why/fulfillment/order/task/OrderExpirationScheduler.java) 的 `closeExpiredOrders` → Repository 的 `findExpiredReservedOrderIds`、`markExpiredForCompensation`。输入是定时触发；下一步扫描并再次条件检查；输出是赢得关单竞争的订单。数据库扫描的截止时间不等于毫秒级准时执行。
4. `OrderApplicationService.retryPendingCompensation` → [InventoryClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/inventory/InventoryClient.java) 的 `release` → [InventoryController.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/web/InventoryController.java) 的 `release`。输入是待释放订单；下一步执行库存释放；输出是释放确认或仍需重试的失败。
5. [InventoryReservationServiceImpl.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/service/impl/InventoryReservationServiceImpl.java) 的 `release` → [InventoryReservationFenceMapper.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/mapper/InventoryReservationFenceMapper.java) 的 `ensureExists`、`selectStatusForUpdate`、`updateStatus`。输入可能是尚未预占的订单；下一步持久化取消栅栏并条件释放锁记录；输出是晚到预占会被拒绝的事实，而不只是一次“释放了零行”。
6. [OrderCompensationScheduler.java](microservices/order-service/src/main/java/com/why/fulfillment/order/task/OrderCompensationScheduler.java) 的 `retryPending` → `retryPendingCompensation` → Repository 的 `markCompensatedIfPending`。输入是后续扫描到的待补偿事实；下一步幂等重试；输出是已补偿或继续保留失败状态。

选读验证：[OrderExpirationSchedulerTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/task/OrderExpirationSchedulerTest.java) 的 `losingConditionalCloseRaceDoesNotReleaseInventory`；[OrderApplicationServiceTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/service/OrderApplicationServiceTest.java) 的 `reserveFinishingAfterCancelNeverReportsReserved`、`duplicateSuccessfulReservationNeverReleasesValidOrder`；[库存测试](microservices/inventory-service/src/test/java/com/why/fulfillment/inventory/InventoryReservationServiceImplTest.java) 的 `cancellationFenceRejectsReserveThatArrivesAfterRelease`。

边界：返回 `CANCELED` 不保证响应时库存已经全部归还。普通补偿任务是定时扫描重试，当前没有统一 owner 租约、指数退避、上限和死信。Inventory 的 MySQL 释放与后续 Redis 条件补偿也不是同一事务。

暂缓：Redis 是否预扣过、条件回补如何判断，交给第 09 课。根目录 [OrderTimeoutScheduler.java](src/main/java/com/why/fulfillment/order/task/OrderTimeoutScheduler.java) 的 Redisson 延迟队列只作为选读对照，不能画进上述微服务调用链。

## 第 09 课：Redis 异步下单——受理、预扣、落库不是同一个时刻

状态：大纲。

读完能回答：为什么先写可靠命令？预扣成功后数据库失败怎么办？为什么有些失败可以回补，有些只能继续重试？

这是独立的快速入口，不是当前购物车结算必经路径。主链：

```text
POST /api/orders/redis
→ Order 持久化 PREPARING 命令
→ HTTP → Inventory Lua 预扣 + 自有账本
→ 已确认则 READY；结果未知仍保留 PREPARING
→ HTTP 202：已受理，不等于订单已创建

后续定时触发：
恢复 PREPARING / 领取 READY 命令
→ owner + 租约 → 创建普通 MySQL 订单及库存预占
→ 标记 Inventory 账本 MATERIALIZED
→ 命令 SUCCEEDED
```

必读顺序：

1. [RedisOrderController.java](microservices/order-service/src/main/java/com/why/fulfillment/order/web/RedisOrderController.java) 的 `create` → [RedisOrderApplicationService.java](microservices/order-service/src/main/java/com/why/fulfillment/order/service/RedisOrderApplicationService.java) 的 `accept`、`prepare`。输入是订单请求；下一步先记意图，再请求预扣；输出是已受理的命令状态或冲突。
2. [RedisOrderCommand.java](microservices/order-service/src/main/java/com/why/fulfillment/order/domain/RedisOrderCommand.java) 与 [RedisOrderCommandRepository.java](microservices/order-service/src/main/java/com/why/fulfillment/order/repository/RedisOrderCommandRepository.java) 的 `insertPreparing`、`findReady`、`claim`。输入是可靠命令；下一步用条件更新认领和恢复过期租约；输出是带 owner 的执行权。
3. [InventoryClient.java](microservices/fulfillment-api/src/main/java/com/why/fulfillment/api/inventory/InventoryClient.java) 的 `reserveRedis` → [InventoryController.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/web/InventoryController.java) 的 `reserveRedis` → [RedisInventoryCoordinator.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/redis/RedisInventoryCoordinator.java) 的 `reserve`。输入是订单号与 SKU；下一步预扣后记录库存服务自己的账本；输出包括“Redis 已动，但账本写入尚未确认”的 UNKNOWN。
4. [RedisStockServiceImpl.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/redis/RedisStockServiceImpl.java) 的 `reserve`、`normalize`、`initializeIfAbsent`、`execute` → [redis_reserve_once.lua](microservices/inventory-service/src/main/resources/lua/redis_reserve_once.lua)。输入是规范化商品签名和 Redis 键；下一步先检查全部 SKU、重复标记和取消标记，再统一扣减；输出是成功、重复成功、冲突、取消或库存不足。
5. [RedisOrderCommandScheduler.java](microservices/order-service/src/main/java/com/why/fulfillment/order/task/RedisOrderCommandScheduler.java) 的 `recoverPreparingCommands`、`persistReadyCommands` → `RedisOrderApplicationService.processReady`。输入是定时触发和领取后的命令；下一步复用第 05 课普通下单，再调用 `InventoryClient.materializeRedis`；输出是订单完成及账本物化结果。
6. [InventoryRedisLedgerService.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/redis/InventoryRedisLedgerService.java) 的 `recordPending`、`recordMaterialized`，回到命令 Repository 的 `markSucceeded`、`scheduleRetry`。输入是前后阶段结果；下一步推进账本/命令并校验 owner；输出为完成或可恢复重试。物化账本不是再次扣 Redis。
7. [redis_compensate_once.lua](microservices/inventory-service/src/main/resources/lua/redis_compensate_once.lua) 与 [redis_compensate_if_reserved.lua](microservices/inventory-service/src/main/resources/lua/redis_compensate_if_reserved.lua)，结合 Coordinator 的 `compensate`、`compensateIfReserved`。输入是不同来源的取消；下一步区别“需要墓碑阻止晚到预扣”与“只在确有 Redis 预扣时回补”；输出是幂等补偿结果。

选读验证：[RedisOrderApplicationServiceTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/service/RedisOrderApplicationServiceTest.java) 的 `persistsIntentBeforeCallingRedisAndKeepsUnknownResultRecoverable`；[RedisOrderCommandSchedulerTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/task/RedisOrderCommandSchedulerTest.java) 的 `projectionPendingSchedulesRetryWithoutCompensationEvenAtRetryLimit`、`unknownReadyFailureNeverCompensatesBecauseOrderMayAlreadyExist`；[周期 11 验收记录](docs/cycle11-schema-isolation-evidence-2026-09-21.md)。

关键边界：

- 202 可能仍是 `PREPARING`，连 Redis 预扣是否完成都未确认；客户端不能直接展示“下单成功”。
- PREPARING 阶段确认多次失败后会尝试带取消墓碑的补偿，成功后进入死信。
- READY/PROCESSING 若订单结果未知，或订单已创建但账本物化失败，继续退避重试；不能因次数到了就给有效订单回补库存。
- Redis 脚本与 MySQL 账本不是一个事务，多键 Lua 也不是已验证的 Redis Cluster 方案。
- 单体 [RedisOrderController.java](src/main/java/com/why/fulfillment/web/RedisOrderController.java) 是“先预扣，再持久化命令”的旧顺序，不能用它解释微服务命令先行。

暂缓：账本状态如何参与对账、差异如何暴露给监控，交给第 10 课。单体异步处理器不逐行重复展开。

## 第 10 课：看见差异——库存对账与异步积压指标

状态：大纲。

读完能回答：Redis 库存比 MySQL 少，是不是一定出错？为什么库存服务不应该读取订单服务的命令表？监控抓取会不会现场扫描业务库？

主链有两个独立触发：

- 对账请求 → Inventory 读取自己的库存与 PENDING 预扣账本 → 读取 Redis → 计算差异/错误 → 返回只读报告并更新最近对账指标。
- 指标刷新定时器 → 统计命令、Outbox、账本状态 → 更新内存 Gauge → Prometheus 抓取这些指标。

必读顺序：

1. [InventoryRedisLedgerService.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/redis/InventoryRedisLedgerService.java) 的 `pendingReservations` → [InventoryRedisReservationRepository.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/redis/InventoryRedisReservationRepository.java) 的 `findPending`、`pendingMetrics`。输入是自有账本；下一步区分 PENDING、MATERIALIZED、COMPENSATED；输出是尚未在 MySQL 库存中体现的预扣数量。
2. [ReconciliationController.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/web/ReconciliationController.java) 的 `inspect` → [StockReconciliationService.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/reconciliation/StockReconciliationService.java) 的 `inspect`。输入是对账请求；下一步在本地只读事务读取数据库事实，并读 Redis；输出为差异和错误报告。
3. 沿 `inspect` 检查期望值：`期望 Redis 可售 = MySQL 可售 - PENDING 账本预扣量`。输入是两个不同阶段的事实；下一步扣除正常在途量；输出不是简单的“两个数字不相等就是异常”。继续看损坏账本、缺失 SKU 和 Redis 未初始化如何报告。
4. [InventoryServiceMetrics.java](microservices/inventory-service/src/main/java/com/why/fulfillment/inventory/metrics/InventoryServiceMetrics.java) 的 `refresh`、`recordReconciliation`。输入分别是定时统计和一次对账结果；下一步更新 Gauge；输出是账本积压、最老年龄及最近差异数。定时刷新账本指标本身不等于执行了完整对账。
5. [OrderServiceMetrics.java](microservices/order-service/src/main/java/com/why/fulfillment/order/metrics/OrderServiceMetrics.java) 的 `refresh` → 命令/Outbox Repository 的统计方法。输入是状态数量；下一步缓存指标；输出为积压和死信信号，不是在 scrape 请求线程内临时查询数据库。
6. [ops/prometheus.yml](ops/prometheus.yml) 的 `scrape_configs` 与 [fulfillment-overview.json](ops/grafana/dashboards/fulfillment-overview.json) 的面板查询。输入是服务指标端点；下一步采集和展示；输出是配置范围内的观测结果。查看目标和查询所用指标后再判断它覆盖单体还是微服务，不要默认整个商城已接入同一套看板。

选读验证：[StockReconciliationServiceTest.java](microservices/inventory-service/src/test/java/com/why/fulfillment/inventory/StockReconciliationServiceTest.java) 的 `reportsMalformedCommandWithoutChangingInventory`；[InventoryServiceMetricsTest.java](microservices/inventory-service/src/test/java/com/why/fulfillment/inventory/metrics/InventoryServiceMetricsTest.java)、[OrderServiceMetricsTest.java](microservices/order-service/src/test/java/com/why/fulfillment/order/metrics/OrderServiceMetricsTest.java)；[周期 12 记录](docs/cycle12-observability-evidence-2026-09-21.md)。

边界：只报告差异，不自动改库存。本地 REPEATABLE_READ 不是 MySQL + Redis 的全局快照，活跃写入期间可能出现短暂差异；指标刷新失败保留旧值，所以数值不变不能单独证明状态正常。配置存在也不等于抓取目标当前为 UP，更不等于已经有生产告警闭环。

暂缓：根目录业务看板的 HTML 和全部面板样式不展开；压测中的延迟与吞吐指标口径下一课讲。

## 第 11 课：验证而不是背数字——测试、限流和压测口径

状态：大纲。

读完能回答：Mock 测试、真实数据库实验、HTTP 联调和压测各自能证明什么？为什么异步受理吞吐不能直接当作最终成交吞吐？

主链：选择待验证行为 → 确认运行路径、数据和环境 → 用测试/压测制造输入 → 同时读取接口结果与最终数据状态 → 区分正确性、受理性能、业务完成及失败恢复 → 得到有范围限制的结论。

必读顺序：

1. [CheckoutServiceTest.java](microservices/commerce-service/src/test/java/com/why/fulfillment/commerce/checkout/service/CheckoutServiceTest.java) 与第 06 课两个真实 MySQL 实验的测试装配。输入是待验证的断言；下一步辨认 Mock 依赖和真实依赖；输出是证据等级：Mock 调用正确不等于真实锁竞争、事务回滚或跨进程恢复已经通过。
2. [MVP 验收记录](docs/mvp-v2-test-evidence-2026-09-24.md)、[V2.1 增量验收](docs/v2-order-actions-evidence-2026-09-24.md)。输入是历史联调场景；下一步核对用户、订单、库存和响应是否共同收敛；输出是本机五进程范围内的证据。V2.1 记录了 119 项微服务测试通过，不能据此说本次又跑了 119 项。
3. 单体 [OrderRedisRateLimitFilter.java](src/main/java/com/why/fulfillment/ratelimit/OrderRedisRateLimitFilter.java) 的 `shouldNotFilter`、`doFilterInternal` → [RedisTokenBucketServiceImpl.java](src/main/java/com/why/fulfillment/inventory/redis/RedisTokenBucketServiceImpl.java) 的 `tryAcquire` → [token_bucket.lua](src/main/resources/lua/token_bucket.lua)。输入是 Redis 下单请求；下一步依次申请全局桶和接口桶；输出为放行或拒绝。两次脚本调用不能称为双桶整体原子操作。
4. [单体 application.yml](src/main/resources/application.yml) 的限流、连接池和调度配置。输入是运行环境；下一步确认开关、阈值和依赖；输出是实验条件。限流默认关闭，且还没有迁入新版 Gateway，不能描述成商城已经全局限流。
5. [cycle5-order-comparison.jmx](performance/cycle5-order-comparison.jmx) → [run-cycle5-jmeter.ps1](performance/run-cycle5-jmeter.ps1)。输入是路径、线程数、持续时间和订单号起点；下一步执行一轮请求与 HTTP 状态断言；输出是 JTL/HTML 报告。脚本只检查退出码和 JTL 是否存在；预热、多轮运行、数据准备/清理以及异步落库核验要另外安排。默认端口是 18080，但旧 JMX 没有 JWT 和商品单价字段，不能因此认为它已适配新版商城；默认压测参数也不是下表的历史实验条件。
6. [performance/README.md](performance/README.md) → [周期 5 证据](docs/cycle5-evidence-2026-09-19.md)。输入是同机三轮结果；下一步比较线程数、升压时间、持续时间、错误率与完成语义；输出为限定环境的中位数结论，不是当前商城的生产容量。

历史数字只作为本课的“读报告样例”：

| 比较项 | 单体 MySQL 同步路径 | 单体 Redis 异步路径 |
| --- | --- | --- |
| HTTP 成功时代表什么 | 订单和库存已同步提交 | 命令已受理，等待异步落库 |
| 记录的吞吐中位数 | 113.30 req/s | 342.91 req/s |
| 记录的 P99 中位数 | 164.52 ms | 76.50 ms |
| 共同条件 | 同机，10 线程，2 秒升压，5 秒持续，预热后 3 轮 | 同左 |

因此，这组证据支持“异步受理响应更快”，不直接支持“新版商城端到端成交能力提升相同比例”。还要检查积压是否清空、最终订单/库存是否一致、错误与补偿是否增加。

选读验证：[OrderRedisRateLimitFilterTest.java](src/test/java/com/why/fulfillment/ratelimit/OrderRedisRateLimitFilterTest.java)、[RedisStockAndTokenBucketIntegrationTest.java](src/test/java/com/why/fulfillment/inventory/redis/RedisStockAndTokenBucketIntegrationTest.java)。其中真实 Redis 实验仍属于单体，不是对微服务 Lua 部署的替代验收。

暂缓：多实例容量、尚未覆盖的故障窗口、真实支付渠道和生产告警治理仍待验证。Commerce/Order 重启与支付发布器崩溃恢复已有本机专项证据，分别见 [可靠性专项](docs/reliability-recovery-2026-09-26.md) 和 [闭环补验](docs/closure-acceptance-2026-09-26.md)；这些结果不能扩大为所有中断场景或多实例恢复均已通过。

## 阅读和运行纪律

1. 先只读源码和断言；看不懂流程时，优先展开对应课程，不先补一堆中间件。
2. 想执行测试时，先确认它是 Mock 单测还是会修改 MySQL/Redis 的集成实验。根目录测试和性能脚本不能对当前商城演示数据直接执行。
3. [V2 启动手册](docs/mvp-v2-runbook-2026-09-24.md) 可作为入口，但与代码不符的旧命令要核对。例如库存实体/Mapper 使用 `stock`、`lock_stock`，不要照抄手册中 `total_stock`、`reserved_stock` 的查询。
4. 数据库初始化、种子和迁移可能覆盖/改变数据。本次没有代你执行，也不要求为了读课先重建数据库。
5. 不把本地密码、JWT 密钥、内部令牌和签名写进学习笔记或 Git 提交。
6. 后续展开一课时，在该课保留编号、补充解释和源码片段；仅在你要求时生成对应的 `practice.md`，不混入尚未讲授的题目。

下一步建议从第 01 课开始建立运行地图；如果已经能画出五个进程的关系，可以直接展开第 04 课“结算”，沿着一次真实下单学习后端核心。
