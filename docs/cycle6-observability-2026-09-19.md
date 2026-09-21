# 周期 6：可观测性与运行看板验收

## 实现

- Spring Boot Actuator 健康、指标和 Prometheus 端点。
- HTTP 请求直方图，可在 Prometheus 中计算 QPS 与 P99。
- 业务指标：MySQL/Redis 两条订单入口、双层限流拒绝、异步命令成功/重试/死信、
  当前积压和库存差异数。
- 应用内运行看板 `/dashboard.html`，每 5 秒刷新订单、异步命令、限流和对账状态。
- Prometheus 采集配置与 Grafana 自动配置，Grafana 预置 QPS、P99、积压、限流、差异面板。
- 看板读取定时对账的最新快照，避免浏览器轮询反复扫描库存；首次无快照时才即时检查。

## 真实应用验收

使用本机 MySQL 8.0.46、Redis 3.0.504，在端口 18080 启动打包后的应用：

- `/actuator/health` 返回 `UP`。
- `/dashboard.html` 返回 HTTP 200。
- `/api/operations/dashboard` 返回订单、命令和库存对账快照。
- `/actuator/prometheus` 包含 `fulfillment_orders_accepted_total`。
- `/actuator/prometheus` 包含 `http_server_requests_seconds_bucket`，可以计算 P99。
- 创建一笔 MySQL 路径测试订单后，看板中的 MySQL 接受计数变为 1。
- Redis 库存未同步该笔 MySQL 路径订单时，对账正确报告 1 条差异，证明差异不是静态演示值。

验收后测试订单已删除，SKU 1001 的 MySQL 库存恢复为 100。全量回归 47 个测试通过，
0 failures、0 errors、0 skipped。

## Docker 监控栈真实验收

2026-09-21 使用 Docker Desktop 4.91.0、Docker Engine 29.8.0 和 Compose 5.5.1
启动 Prometheus 2.54.1 与 Grafana 11.2.0，并对运行中的应用完成验收：

- `fulfillment-prometheus`、`fulfillment-grafana` 容器持续运行，分别监听 9090、3000 端口。
- Prometheus 实际抓取 `host.docker.internal:8080/actuator/prometheus`，目标状态为 `UP`，
  `lastError` 为空。
- Grafana `/api/health` 返回数据库状态 `ok`。
- Grafana 自动加载名为 `Prometheus` 的数据源和名为 `Fulfillment` 的预置看板。
- 应用 `/actuator/health` 返回 `UP`，`/dashboard.html` 返回 HTTP 200；Prometheus 端点同时
  包含业务计数器和 HTTP 请求直方图。

当前本机 MySQL、Redis 使用宿主机服务，因此 Compose 验收只启动 `prometheus` 和 `grafana`，
避免 3306、6379 端口冲突。完整的新环境仍可通过 `.env` 设置密码后执行
`docker compose up -d` 启动全部依赖。
