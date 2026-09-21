# 周期 11：Order / Inventory 数据边界隔离验收

## 目标

将微服务切片从“独立进程、共享业务库”推进到可验证的数据所有权边界：

- Order 只访问 `fulfillment_order`。
- Inventory 只访问 `fulfillment_inventory`。
- Inventory 对账不再读取 Order 私有的 `microservice_order_command`。
- Redis 快速下单在拆库后仍能完成预扣、异步落库、超时关单和双存储补偿。

两个 schema 当前仍位于同一个 MySQL 8.0.46 实例，本轮不声称实例级故障隔离。

## 实现

### 数据所有权

| 所有者 | Schema | 表 |
|---|---|---|
| Order | `fulfillment_order` | `order`、`order_item`、`order_outbox_event`、`microservice_order_command` |
| Inventory | `fulfillment_inventory` | `sku_stock`、`sku_stock_lock`、`inventory_reservation_fence`、`inventory_redis_reservation` |

`migration-cycle11-split-schema.sql` 在停机窗口创建目标 schema，并以 `INSERT IGNORE` 从旧 `fulfillment` 复制数据。脚本连续执行两次成功，源库与目标库的订单、Outbox、库存、锁定记录和栅栏行数一致。

### 最小权限

本机创建了两个未写入仓库的随机密码账号：

- `fulfillment_order_app@127.0.0.1`：仅拥有 `fulfillment_order.*` 的 `SELECT, INSERT, UPDATE`。
- `fulfillment_inventory_app@127.0.0.1`：仅拥有 `fulfillment_inventory.*` 的 `SELECT, INSERT, UPDATE`。

实际验证结果：两个账号均能读取自己的 schema；Order 账号读取 Inventory 被拒绝，Inventory 账号读取 Order 命令表被拒绝。

### Inventory 自有预扣账本

新增 `inventory_redis_reservation`：

- `PENDING`：Redis 已预扣，MySQL 订单和库存锁尚未完成。
- `MATERIALIZED`：Order 的 MySQL 订单和 Inventory 锁定库存已完成。
- `COMPENSATED`：Redis 预扣已恢复。

Redis Lua 与 MySQL 账本不是同一个事务。恢复边界为：Order 先持久化命令；若 Redis 已扣但账本写入失败，命令保留可重试，重复预扣命中 Redis 幂等标记后补写账本。Order 落库后通过内部接口物化账本。READY/PROCESSING 阶段的未知异常只退避重试，因为调用方无法证明订单尚未形成；只有 `createPending` 返回明确失败终态时才执行 Redis 补偿。

对账公式改为：

```text
期望 Redis 库存 = Inventory MySQL 可售库存 - PENDING 账本数量
```

Inventory 不再查询 `microservice_order_command`。

## 自动化验证

### 微服务 Reactor

```text
Inventory Service: 17 tests
Order Service:     23 tests
Payment Service:    3 tests
Gateway:            1 test
Total:             44 tests
Failures:           0
Errors:             0
```

新增测试覆盖账本载荷归一化、冲突检测、状态转换、损坏 JSON、对账扣减、物化后命令成功，以及物化或订单数据库出现未知异常时只重试且绝不执行 Redis 补偿。

### 单体回归

停止微服务进程后，根目录单体 47 项测试全部通过。这样避免两个运行路径同时操作旧回归库造成调度器干扰。

### 迁移兼容

共享库升级脚本会把仍为 READY/PROCESSING 的旧 Redis 命令投影为 Inventory `PENDING` 账本。旧命令 JSON 中的 `price` 被移除，只保留 `skuId/spuId/count`。实际插入一条带价格字段的旧命令后，回填结果为：

```json
[{"skuId":992012,"spuId":9920,"count":1}]
```

## 真实四进程验收

运行环境：Gateway 18080、Order 18081、Inventory 18082、Payment 18083；Order 与 Inventory 使用各自最小权限账号；Redis 位于 6379。

测试订单 `992011`、SKU `992011`，初始 MySQL 和 Redis 库存均为 20，购买 2 件，超时 3 秒。

异步落库完成后：

```text
HTTP:                         202 ACCEPTED
microservice_order_command:   SUCCEEDED
order:                        PENDING_PAYMENT / RESERVED
Inventory MySQL:              stock=18, lock_stock=2
sku_stock_lock:               LOCKED, count=2
inventory_redis_reservation:  MATERIALIZED
Redis stock:                  18
目标 SKU 对账差异:            0
```

超时关单后：

```text
order:                        CANCELED / COMPENSATED
Inventory MySQL:              stock=20, lock_stock=0
sku_stock_lock:               RELEASED
inventory_redis_reservation:  COMPENSATED
Redis stock:                  20
目标 SKU 对账差异:            0
对账错误:                     0
```

测试数据和 Redis 标记已清理，四个联调进程已停止。

## 当前边界

- schema 与账号隔离已经完成，MySQL 实例仍共享。
- 账本投影失败会无限退避重试，避免错误补偿；积压指标、告警和人工恢复入口仍需补充。
- 对账只报告差异，不自动修复。
- 服务仍使用静态 URL，尚未接入服务发现、多实例故障注入和分布式追踪。
