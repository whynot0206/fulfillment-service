# 周期 12 微服务可观测性验收（2026-09-21）

## 目标

为 Gateway、Order、Inventory、Payment 四个微服务提供可抓取的 Prometheus 端点，并让关键恢复状态能够在运行时被观察。

## 实现

- 四个微服务引入 `micrometer-registry-prometheus`，继续暴露 `/actuator/health`、`/actuator/info` 和 `/actuator/prometheus`。
- `ops/prometheus.yml` 保留周期 6 的单体 `8080` 抓取任务，并新增四个本地微服务端口 `18080`–`18083`。
- 四个微服务设置稳定的 `application` 标签并开启 HTTP 请求 histogram，Grafana 可同时聚合单体与微服务请求量和 P99。
- Order 指标：Redis 下单命令状态、支付确认 Outbox 状态。
- Inventory 指标：Redis 预占账本 PENDING 数量、最老 PENDING 年龄、最近一次只读对账差异数量和错误数量。
- 指标由定时任务刷新到原子值；数据库读取失败时保留上一次成功值，不在 Prometheus 请求线程中执行查询。

## 自动化验证

在 `microservices` 目录执行：

```text
mvn -q -DskipTests compile
mvn -q test
```

两条命令均通过。新增测试覆盖订单状态映射、空状态归零、库存账本指标刷新和对账差异记录。

## 边界

本周期完成了指标代码、抓取配置和看板查询迁移；未宣称已完成 Nacos 服务发现、多实例真实故障注入、告警路由、分布式追踪或人工补偿接口。Prometheus targets 的 `UP` 状态需要在本地四进程和 Docker Prometheus 同时启动后再做环境验收。
