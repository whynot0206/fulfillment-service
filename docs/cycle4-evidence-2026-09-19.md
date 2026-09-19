# 周期 1 与周期 4 实测记录（2026-09-19）

## 环境

- Windows 11
- MySQL Community Server 8.0.46，`innodb_deadlock_detect = 1`
- Temurin JDK 17.0.20.1
- Apache Maven 3.9.16
- HikariCP 最大连接数 20
- MyBatis SQL 调试日志关闭

执行命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1
```

完整结果：14 个测试通过，0 失败，0 错误，Maven `BUILD SUCCESS`。

## M0：超卖与原子扣减

同一套 300 线程、初始库存 100、每单 1 件的测试结果：

| 方案 | 成功请求 | 库存不足 | 异常 | 实际扣减 | 超卖件数 | 耗时 |
|---|---:|---:|---:|---:|---:|---:|
| 先查后按绝对值写回 | 300 | 0 | 0 | 18 | 282 | 699 ms |
| 单条条件更新原子扣减 | 100 | 200 | 0 | 100 | 0 | 673 ms |

这里的超卖不是负库存。先查后扣中多个线程读到相同旧值，后写覆盖先写，所以 300 个请求都返回成功，数据库实际只扣了 18 件。

## 周期 4：多 SKU 死锁复现与修复

测试入口：`Cycle4DeadlockIntegrationTest`。

| 方案 | 事务数 | 死锁回滚/失败 | 死锁率 |
|---|---:|---:|---:|
| 相反 SKU 顺序，不排序 | 40 | 20 | 50% |
| 相反输入，扣减前按 `skuId` 排序 | 40 | 0 | 0% |

未排序基线用屏障保证两个事务分别先持有 SKU 1001 和 SKU 1002 的排他行锁，再申请对方持有的行锁。每轮 MySQL 选择一个事务回滚，因此 20 轮、40 个事务中有 20 个死锁牺牲者。

`SHOW ENGINE INNODB STATUS` 的最近一次死锁记录显示：

```text
事务 1：持有 sku_id=1001 的 PRIMARY X 锁，等待 sku_id=1002
事务 2：持有 sku_id=1002 的 PRIMARY X 锁，等待 sku_id=1001
MySQL: WE ROLL BACK TRANSACTION (2)
```

原始关键片段见 `cycle4-innodb-deadlock-2026-09-19.txt`。

正式预占服务先合并同一订单中的重复 SKU，再按 `skuId` 升序申请行锁。两个事务会竞争同一个首行锁，不再形成循环等待；40 个事务全部完成。

## 数据清理

测试在每个用例后删除订单号 `9000000` 到 `9000039` 的测试锁定记录，并把 SKU 1001、1002 恢复为各 100 件可售库存、0 件锁定库存。
