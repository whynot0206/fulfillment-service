# 高并发库存与订单履约中台

## 当前实现状态

- M0：已完成并实测。连接池上限 20、300 个请求的一次复验中，普通先查后扣超卖 245 件，加本地事务后仍超卖 278 件，原子 SQL 超卖 0 件。
- M1：已完成真实 Redis 端到端验收。2 秒超时订单自动取消，预占的 3 件库存完整释放，可售库存恢复且锁定库存归零。
- 周期 3：已完成支付回调幂等、本地消息表、抢占发布、失败退避和库存确认消费。依据规划书的退出条件，当前保留单模块服务边界，Nacos、OpenFeign 和 Gateway 延后到确有部署需求时再拆。
- 项目本地工具链已安装 Temurin JDK 17.0.20.1 和 Maven 3.9.16；MySQL 8.0.46、`fulfillment` 测试库和本机 Redis 服务均已就绪。
- 周期 4：已完成真实 MySQL 对照。未排序基线 40 个事务中死锁回滚 20 个，死锁率 50%；按 `skuId` 排序后 40 个事务全部成功，死锁率 0%。
- 周期 5：已完成 Redis Lua 多 SKU 原子预扣与幂等补偿、持久化命令异步落库、库存对账、双层令牌桶和 JMeter 三轮对比。全量 46 个测试通过。
- 周期 6：已完成 Actuator、Prometheus 指标、Grafana 预置面板和应用内运行看板；Docker 实测 Prometheus 采集目标为 `UP`，Grafana 数据源和 `Fulfillment` 看板可自动加载。当前全量 47 个测试通过。

M1 的核心边界是：订单与库存预占在本地事务中提交，订单提交后才投递延迟任务；关单只允许把待支付订单改为已取消，随后幂等释放锁定库存。

周期 3 的消息边界是：支付状态更新和 `PAYMENT_CONFIRMED` 事件在同一数据库事务内落库；发布器提供至少一次投递，库存确认按锁定记录状态条件更新保证重复消费不重复扣库存。增量表结构见 `sql/migration-cycle3.sql`。

> 完整方案见 `D:\vibecoding\履约中台-项目规划书.md`
> 当前工程仍是单模块实现，但包边界已经按后续 inventory、order、payment 服务划分。

## 这个目录里，哪些是搭好的，哪些是你的

当前目录已经包含库存原子扣减、库存预占与释放、超时关单、支付回调、本地消息表和库存确认消费的实现，以及对应的单元测试。

包名是 `inventory` 不是 `stock`，因为周期 3 拆出来的服务叫 `inventory-service`——从现在就按未来的服务边界分包，拆分时直接把包提出来变模块即可。后面写订单和支付逻辑同理，放进 `.order` 和 `.payment`，跨包不要直接调对方的 service 实现类。

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

## 本地 HTTP 入口

- `POST /api/orders`：创建待支付订单并预占多 SKU 库存，可传 `timeoutSeconds`。
- `POST /api/payments/callbacks/success`：接收支付成功回调，同一订单与交易号重复提交按幂等成功处理。
- `GET /api/inventory/skus/{skuId}`：查询 SKU 的可售库存和锁定库存。
- `POST /api/orders/redis`：Redis 原子预扣并写入可靠异步命令，返回 202。
- `GET /api/inventory/reconciliation`：输出 MySQL 与 Redis 的库存差异清单，不自动修正。
- `GET /api/operations/dashboard`：返回运行看板所需的订单、积压、限流和对账快照。
- `/dashboard.html`：本地运行看板页面。
- `/actuator/prometheus`：Prometheus 指标端点。

这些入口用于本地联调；周期 5 的 JMeter 计划和运行脚本在 `performance` 目录。

## 可观测性

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

M0 这两个数字是正确性数字（超卖件数），不是性能数字（QPS）；性能数字等周期 5 的 JMeter 压测。
