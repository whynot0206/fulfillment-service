# 高并发库存与订单履约中台

## 当前实现状态

仓库包含两条运行路径：根目录单体是周期 0–6 的完整实验与回归基线；`microservices` 是周期 7 的跨进程交易切片。两者的功能覆盖不同，统一边界见 [`docs/project-function-boundary.md`](docs/project-function-boundary.md)。

- 周期 1（旧文档简称 M0）：已完成并实测。连接池上限 20、300 个请求的一次复验中，普通先查后扣超卖 245 件，加本地事务后仍超卖 278 件，原子 SQL 超卖 0 件。
- 周期 2（旧文档简称 M1）：已完成真实 Redis 端到端验收。2 秒超时订单自动取消，预占的 3 件库存完整释放，可售库存恢复且锁定库存归零。
- 周期 3：已完成单体内的支付回调幂等、本地消息表、抢占发布、失败退避和库存确认消费。这里验证的是业务包边界和本地事务语义，不是独立服务部署。
- 项目本地工具链已安装 Temurin JDK 17.0.20.1 和 Maven 3.9.16；MySQL 8.0.46、`fulfillment` 测试库和本机 Redis 服务均已就绪。
- 周期 4：已完成真实 MySQL 对照。未排序基线 40 个事务中死锁回滚 20 个，死锁率 50%；按 `skuId` 排序后 40 个事务全部成功，死锁率 0%。
- 周期 5：已完成 Redis Lua 多 SKU 原子预扣与幂等补偿、持久化命令异步落库、库存对账、双层令牌桶和 JMeter 三轮对比。全量 46 个测试通过。
- 周期 6：已完成 Actuator、Prometheus 指标、Grafana 预置面板和应用内运行看板；Docker 实测 Prometheus 采集目标为 `UP`，Grafana 数据源和 `Fulfillment` 看板可自动加载。当前全量 47 个测试通过。
- 周期 7：新增 `microservices` 运行切片，包含 Gateway、Order、Inventory、Payment 四个独立进程和一个 DTO/Feign 契约模块；在本机、共享 MySQL、静态服务 URL 条件下验证了下单预占、支付、Outbox 确认和故障补偿。超时关单、Redis 快速下单、对账、限流和业务看板尚未迁移到该切片。
- 周期 8：微服务订单创建增加请求幂等和 `order_item` 明细持久化。同一 `orderId` 与相同载荷安全重放，载荷不同返回 409；真实 Gateway 验收确认重复请求不重复扣库存。微服务 Reactor 共 26 项测试通过。

周期 2 的核心边界是：订单与库存预占在本地事务中提交，订单提交后才投递延迟任务；关单只允许把待支付订单改为已取消，随后幂等释放锁定库存。

周期 3 的消息边界是：支付状态更新和 `PAYMENT_CONFIRMED` 事件在同一数据库事务内落库；发布器提供至少一次投递，库存确认按锁定记录状态条件更新保证重复消费不重复扣库存。增量表结构见 `sql/migration-cycle3.sql`。

> 完整方案见 [`docs/project-plan-v3.1.md`](docs/project-plan-v3.1.md)；工作区源文件位于 `D:\vibecoding\履约中台-项目规划书.md`。
> 当前实现与对外口径以 [`docs/project-function-boundary.md`](docs/project-function-boundary.md) 为准。

## 两条运行路径

根目录应用运行在 8080，保留周期 0–6 的完整实验能力。`microservices` 目录的 Gateway、Order、Inventory 和 Payment 分别独立启动，用于验证真实 HTTP 边界、Saga 补偿和跨进程 Outbox。

微服务切片当前仍与单体共用 `fulfillment` 数据库和表。联调微服务时应停止单体，避免两个 Outbox 发布器同时消费 `order_outbox_event`。微服务启动和安全参数见 [`microservices/README.md`](microservices/README.md)。

`NOTES-对照记录.md` 保留了每个周期与 mall4cloud 的差异记录，后续周期继续在这里补充真实实验结论。

## 跑起来的顺序

当前使用本机 `MySQL80` 服务和 `fulfillment` 数据库。新环境可执行 `sql/schema.sql` 初始化；若使用 Docker，再运行 `docker compose up -d`。

启动应用或运行测试前，通过环境变量提供数据库密码：PowerShell 使用
`$env:MYSQL_PASSWORD = '<你的本地密码>'`。Docker Compose 请先复制 `.env.example`
为 `.env` 并填写 `MYSQL_ROOT_PASSWORD`；`.env` 已被 Git 忽略，不要提交真实密码。

直接运行 `powershell -ExecutionPolicy Bypass -File .\run-tests.ps1`。脚本使用 `D:\vibecoding\.toolchains` 中的项目本地 JDK/Maven，不要求系统环境变量。

先跑 `StockConcurrencyTest`：`naive_shouldOversell` 用于复现超卖，`atomic_shouldNotOversell` 要求超卖归零。再跑 `Cycle4DeadlockIntegrationTest`，它在真实 MySQL 上用两个反向 SKU 请求验证统一排序后的死锁次数。

本次实测数据和 MySQL 死锁日志摘要保存在 `docs/cycle4-evidence-2026-09-19.md`，
Redis 延迟关单验收记录保存在 `docs/redis-timeout-e2e-2026-09-19.md`。
周期 5 的环境、三轮数据和语义边界保存在 `docs/cycle5-evidence-2026-09-19.md`。
周期 6 的可观测性验收保存在 `docs/cycle6-observability-2026-09-19.md`。
周期 7 的多进程主链路与故障补偿验收保存在 `docs/cycle7-microservices-evidence-2026-09-21.md`。
周期 8 的订单幂等与明细验收保存在 `docs/cycle8-order-idempotency-evidence-2026-09-21.md`。

## 单体 HTTP 入口（8080）

- `POST /api/orders`：创建待支付订单并预占多 SKU 库存，可传 `timeoutSeconds`。
- `POST /api/payments/callbacks/success`：接收支付成功回调，同一订单与交易号重复提交按幂等成功处理。
- `GET /api/inventory/skus/{skuId}`：查询 SKU 的可售库存和锁定库存。
- `POST /api/orders/redis`：Redis 原子预扣并写入可靠异步命令，返回 202。
- `GET /api/inventory/reconciliation`：输出 MySQL 与 Redis 的库存差异清单，不自动修正。
- `GET /api/operations/dashboard`：返回运行看板所需的订单、积压、限流和对账快照。
- `/dashboard.html`：本地运行看板页面。
- `/actuator/prometheus`：Prometheus 指标端点。

这些入口属于根目录单体。周期 5 的 JMeter 计划和运行脚本在 `performance` 目录。

## 微服务切片 HTTP 入口（Gateway 18080）

- `POST /api/orders`：持久化订单与商品明细并编排库存预占；商品项包含 `skuId`、`spuId`、`count` 和 `price`。
- `GET /api/orders/{orderId}`：查询订单主状态、预占状态和商品明细。
- `POST /api/payments/callbacks/success`：支付成功回调，必须携带时间戳和 HMAC 签名。
- `GET /api/inventory/skus/{skuId}`：查询库存。

库存预占、释放和确认只位于服务内部 `/internal/**`，需要 `X-Internal-Service-Token`。该切片尚未提供超时关单、Redis 快速下单、对账、限流和业务看板。

## 单体可观测性

直接启动应用后访问 `http://localhost:8080/dashboard.html` 查看轻量运行看板。需要长期趋势时，
在 `.env` 中配置 `MYSQL_ROOT_PASSWORD` 和 `GRAFANA_ADMIN_PASSWORD`。如果 MySQL、Redis 已在
宿主机运行，只执行 `docker compose up -d prometheus grafana`；全新环境再执行
`docker compose up -d` 启动全部依赖。
Prometheus 位于 9090，Grafana 位于 3000。预置 Grafana 看板展示 HTTP QPS、P99、异步命令
积压、限流拒绝和库存差异。

## 写代码前先回答这几个问题

不是形式主义。这几问就是面试追问的原文，你现在答不上来，写完代码也还是答不上来。

先查后扣为什么会超卖——具体是哪两步之间被插进了别的线程？给它加上 `@Transactional` 能不能解决，为什么？MySQL 默认 RR 隔离级别下，两个事务都 `select` 到 stock=100，各自写回 99，最终库存是多少、卖出去几件？

原子 SQL 那条 `where` 里的 `#{count} <= stock`，判断发生在哪里、由谁保证它和更新之间没有间隙？`update` 返回 0 行和抛异常，对调用方是两件不同的事，你的 Service 打算怎么区分？

## 对照式实现：先写，再对照，把差异记下来

这是这个项目区别于"又一个 Spring Boot demo"的地方，也是你之前说的"借鉴而非照抄"具体怎么落地。

顺序不能反：**先自己写完**，再打开 mall4cloud 的 `mall4cloud-product/src/main/resources/mapper/SkuStockMapper.xml` 和 `SkuStockLockServiceImpl.java` 对照。反过来先看再写，你得到的是它的答案，不是你的理解。

对照完在 `NOTES-对照记录.md` 里按这个格式记：

```
## 2026-XX-XX 库存原子扣减
我的写法：
mall4cloud 的写法：
差异：
谁更好，为什么：
```

**"谁更好"这一栏允许你写 mall4cloud 更好，也允许写它有问题。** 后者尤其值钱——第 4 周那个多 SKU 死锁就是这么来的：它在循环里逐个 SKU 扣减且没排序（`SkuStockLockServiceImpl.java:97,102`），你自己写的时候大概率也想不到要排序。等你压测复现出死锁、再回头发现工业代码也踩了同一个坑，这就是一个完整的故事，而且只有你有。

## 关于数字

`application.yml` 里的连接池大小、测试里的线程数，改造前后必须一致，否则两组数字不可比。`log-impl` 那行调试完记得注释掉——SQL 日志的 IO 开销会让性能数字整个失真。

方案文档第四节写了让数字站得住的五个要求，第 5 周正式压测前回去再读一遍。

周期 1 的这些数字是正确性数字（超卖件数），不是性能数字（QPS）；性能数字见周期 5 的 JMeter 压测。
