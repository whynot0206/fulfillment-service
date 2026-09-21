# 周期 9：微服务超时关单验收

## 实现范围

- Order Service 持久化 `timeout_seconds` 和 `expire_time`，默认 1800 秒，允许 1 秒到 7 天。
- 到期扫描只查询 `PENDING_PAYMENT / RESERVED` 订单，并使用带订单状态、预占状态和到期时间条件的 UPDATE 领取关单。
- 领取成功后订单转为 `CANCELED / PENDING_COMPENSATION`，随后调用 Inventory 的幂等释放接口。
- 释放成功后订单转为 `CANCELED / COMPENSATED`；释放失败时保留待补偿状态，由现有补偿任务继续重试。
- 支付更新同样要求订单仍为待支付且已预占，因此支付与关单竞争时只有一个条件更新能成功。
- 超时时长属于订单创建幂等载荷；同一订单号使用不同超时时长会返回冲突。

## 数据库迁移

`microservices/sql/migration-cycle9.sql` 已在 MySQL 8.0.46 的 `fulfillment` 库连续执行两次成功。最终新增结构：

```text
timeout_seconds BIGINT NOT NULL DEFAULT 1800
expire_time     DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59'
idx_order_expiration(status,reservation_status,expire_time,order_id)
```

历史订单按 `create_time + timeout_seconds` 回填到期时间。远期默认值仅兼容仍由 Redisson 延迟队列处理的根目录单体写入；微服务创建订单会显式写入真实截止时间。

初始化脚本以及周期 7、8、9 迁移已在临时数据库连续执行成功；事务内使用单体旧字段集合插入订单也已验证成功并回滚。

## 自动化验证

微服务 Reactor 最终回归：

- Inventory：8 项
- Order：16 项
- Payment：3 项
- Gateway：1 项
- 合计：28 项，0 failures，0 errors，`BUILD SUCCESS`

新增测试覆盖：领取到期订单后立即尝试释放库存；条件领取失败时不释放库存，避免支付分支已获胜后错误回补。

根目录单体回归在提供本地 `MYSQL_PASSWORD` 后为 47 项，0 failures，0 errors，`BUILD SUCCESS`。

## 真实超时关单

本机四进程通过 Gateway 创建订单 `990001`，初始 SKU 库存为可售 20、锁定 0，请求数量 2、`timeoutSeconds=2`。

```text
创建响应：HTTP 201
完成耗时：约 2.64 秒
最终订单：CANCELED / COMPENSATED
最终库存：可售 20、锁定 0
锁定记录：status=2（已释放），1 条
```

这条链路确认订单到期后先保留待补偿事实，再通过 Inventory 释放库存；重复扫描不会重复释放。

## 支付与关单互斥

订单 `990002` 使用独立 SKU、`timeoutSeconds=3`。创建后立即通过带 HMAC 的 Payment 回调支付，等待 5 秒后核对：

```text
订单：PAID / RESERVED
库存：可售 18、锁定 0
库存锁定记录：status=3（已确认），1 条
Outbox：status=2（已发送），retry_count=0
```

超过到期时间后订单仍保持已支付，扫描器没有错误取消或释放库存。这里的 `RESERVED` 是订单侧“预占阶段成功”记录，库存锁定已经确认归零。

验收后两个测试订单、明细、Outbox、锁定记录和栅栏均已删除，两个测试 SKU 恢复为可售 20、锁定 0。

## 当前边界

- 当前使用数据库索引扫描，不依赖 Redis 延迟队列，优点是任务随订单持久化并可在重启后恢复；代价是需要控制扫描频率和索引成本。
- 默认每秒扫描 50 条；正常释放会立即执行，失败后进入每 5 秒最多 50 条的补偿扫描。百万级积压、分片扫描和多实例容量尚未验证。
- 多实例可重复发现同一订单，但条件 UPDATE 只允许一个实例领取；尚未进行真实多实例故障注入。
- 当前策略是支付和关单谁先完成条件更新谁获胜，不额外拒绝“已到期但扫描器尚未领取”的支付。
