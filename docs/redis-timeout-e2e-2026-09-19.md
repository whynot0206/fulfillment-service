# Redis 延迟关单端到端证据（2026-09-19）

## 环境

- Redis Windows 服务：Running
- Redis 地址：`127.0.0.1:6379`
- `redis-cli ping`：`PONG`
- 应用端口：`18080`（仅本次验收使用）
- MySQL：8.0.46，数据库 `fulfillment`

## 场景

通过 `POST /api/orders` 创建订单 `99000001`，对 SKU `1001` 预占 3 件，
并将 `timeoutSeconds` 设为 2。创建接口返回成功后等待 5 秒，再查询数据库。

## 结果

```text
order_id  status
99000001  3

sku_id  stock  lock_stock
1001    100    0

order_id  sku_id  count  status
99000001  1001    3      2
```

状态含义：订单 `3` 为已取消，库存锁 `2` 为已释放。说明订单事务提交后任务成功
进入 Redisson 延迟队列，消费者到期后完成条件关单，并将可售库存从预占状态完整恢复。

验收结束后已删除订单、库存锁和相关 outbox 测试数据，并把 SKU `1001` 恢复为
`stock=100, lock_stock=0`。
