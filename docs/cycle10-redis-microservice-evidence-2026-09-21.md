# 周期 10：微服务 Redis 快速路径验收记录

## 验收范围

本周期把单体中的 Redis Lua 多 SKU 预扣、可靠异步订单落库和库存对账迁入四进程微服务切片。验收环境为本机 Gateway、Order、Inventory、Payment 四个 Java 进程，共享 MySQL `fulfillment`，本机 Redis 6379，服务地址为静态 URL。

## 实现边界

- Order Service 先写入 `microservice_order_command`，持久化完整商品价格与超时信息，再调用 Inventory Service 预扣。
- Inventory Service 用一段 Lua 原子处理多 SKU 校验、扣减和订单载荷签名标记。
- 后台任务通过数据库租约领取 READY 命令，复用普通订单创建和 MySQL 库存预占链路。
- 结果未知或落库失败时，强制补偿会写取消墓碑；补偿先到时，晚到预扣被拒绝。
- 超时关单释放 MySQL 锁定库存后，再对存在 Redis 预扣标记的订单执行条件补偿。
- 对账只读输出差异，公式为 `期望 Redis = MySQL 可售库存 - READY/PROCESSING 命令预扣量`，不自动修复。

## 自动化验证

微服务 Reactor 共 37 项测试通过，包含：

- Inventory：13 项，覆盖普通预占、释放、确认、Controller，以及 Redis 重放、取消墓碑和无预扣条件补偿。
- Order：20 项，覆盖订单幂等、超时竞争、支付、Outbox、Redis 命令接收和租约任务的成功及失败分支。
- Payment：3 项。
- Gateway：1 项。

根目录单体 47 项回归测试继续作为周期 0–6 基线。

首次在四个微服务仍运行时执行单体回归，微服务补偿任务释放了 SKU 1001 的 3 件历史锁定库存，使原子扣减量具读到 103 次成功并失败。停止四个微服务、按共享库运行约束隔离重跑后，47 项全部通过；原子路径为 100 次成功、200 次库存不足、超卖 0。

## 真实跨进程验收

测试订单 `991001`、SKU `991001`，MySQL 初始库存为 20，Redis 请求数量为 2，超时为 30 秒。

1. 通过 Gateway 调用 `POST /api/orders/redis`，返回 HTTP 202，命令编号为 1。
2. 后台处理后，订单为 `PENDING_PAYMENT / RESERVED`，商品明细已保存；MySQL 库存为 18、锁定量为 2，Redis 库存为 18，命令为 `SUCCEEDED` 且重试次数为 0。
3. 对账结果未报告该 SKU，说明当时 MySQL 与 Redis 值一致。
4. 到期扫描把订单改为取消和待补偿。验收中首先暴露出只读事务执行 `SELECT ... FOR UPDATE` 导致 Inventory 返回 500；改为普通只读查询后重新构建并调用补偿。
5. 补偿完成后，订单为 `CANCELED / COMPENSATED`，MySQL 库存恢复为 20、锁定量为 0，锁记录为已释放，取消栅栏为已取消，Redis 库存恢复为 20。

另用订单 `991002` 验证补偿先到：Inventory 先返回 `COMPENSATED` 并写取消墓碑，随后相同载荷的预扣返回 `CANCELED`，Redis 库存保持 20，没有发生晚到扣减。

`schema.sql` 后连续执行周期 7 至 10 迁移两轮成功，临时数据库包含 8 张表，命令表主键、订单唯一键、READY 扫描索引和对账索引均存在。

该缺陷和修复说明真实跨进程验收覆盖到了单元测试没有触发的数据库事务限制。

## 可使用与不可外推的结论

可以表述：本机四进程环境下，Redis 预扣、可靠命令、异步订单创建、超时双存储补偿和只读对账已经跑通；接口通过订单号、载荷签名、状态条件和租约支持安全重试。

不能表述：Redis 与 MySQL 具有强一致事务、消息 exactly-once、已完成物理数据库隔离、支持 Redis Cluster、多实例生产高可用或已经得到微服务容量结论。Inventory 对账读取 Order 命令表是共享数据库阶段的折中。
