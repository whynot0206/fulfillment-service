# 周期 8：订单创建幂等与明细持久化验收

## 实现范围

- Order Service 在同一个本地事务中写入 `order` 和 `order_item`。
- `order_item` 增加 `(order_id, sku_id)` 唯一约束，一个订单内禁止重复 SKU。
- 创建请求商品项增加 `price`，订单查询返回完整商品明细。
- 重复 `orderId` 会比较用户、订单金额和排序后的全部商品字段。
- 相同载荷返回已有状态；不同载荷返回 `CONFLICT`，HTTP 409。
- 已有订单停在 `RESERVING` 时，重放会重新调用具备幂等语义的 Inventory 预占接口。

## 数据库迁移

`microservices/sql/migration-cycle8.sql` 已在本机 MySQL 8.0.46 的 `fulfillment` 库连续执行两次，均成功。最终索引为：

```text
PRIMARY                         id
idx_order                       order_id
uk_order_item_order_sku         order_id,sku_id
```

## 自动化验证

执行整个 `microservices` Maven Reactor：

- Inventory：8 项
- Order：14 项
- Payment：3 项
- Gateway：1 项
- 合计：26 项，0 failures，0 errors，`BUILD SUCCESS`

新增 Order 测试覆盖：

- 已完成的相同请求直接返回幂等成功，不再次调用库存。
- `RESERVING` 状态的相同请求安全恢复库存预占。
- 同一订单号、不同商品载荷返回冲突。
- 幂等重放返回 HTTP 200，非法请求返回 HTTP 400 Problem Detail。
- 超过 `DECIMAL(12,2)` 精度的金额在写库前被拒绝，避免数据库舍入导致后续重放误判冲突。

## Gateway 真实联调

运行条件：四个服务本机启动，Gateway `18080`，Order `18081`，Inventory `18082`，Payment `18083`；四个健康端点均为 `UP`。测试订单 `980001` 使用一个 SKU，数量 2、单价 9.99。

| 操作 | HTTP | 结果 |
|---|---:|---|
| 首次创建 | 201 | `RESERVED`，`replayed=false` |
| 完全相同载荷重放 | 200 | `RESERVED`，`replayed=true` |
| 仅把单价改为 8.88 后重放 | 409 | `CONFLICT` |
| 查询订单 | 200 | 返回一条 SKU、SPU、数量和单价完整的商品明细 |
| 使用三位小数单价创建 | 400 | 在写库前拒绝，不发生 MySQL 自动舍入 |

数据库核对结果：

```text
order_item 条数：1
SKU 可售/锁定库存：18 / 2（初始 20 / 0）
sku_stock_lock 条数：1
```

这说明相同请求重放没有重复写明细，也没有重复扣减或重复创建库存锁定。验收后已删除测试订单、明细、锁定记录和栅栏，并把测试 SKU 恢复为可售 20、锁定 0。

## 当前边界

- 该能力只落在微服务 Order Service；根目录单体普通 MySQL 下单入口仍未持久化明细，也没有相同的请求幂等语义。
- 当前直接使用业务 `orderId` 作为幂等键，尚未增加独立的客户端请求号或幂等键有效期。
- 商品明细在创建后按不可变数据处理；尚未实现改价、拆单、售后或订单版本控制。
- 本次真实联调是单机串行重放。自动化测试覆盖状态分支，但同一订单号的多线程数据库竞争仍可增加专门集成测试。
