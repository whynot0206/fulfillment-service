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

## 运行边界

本机当前没有 Docker CLI，因此 Prometheus/Grafana Compose 配置已完成静态校验和 Grafana
JSON 解析，尚未在本机拉起容器。安装 Docker 后，在 `.env` 中设置 MySQL 与 Grafana 密码，
默认应用端口 8080 启动后执行 `docker compose up -d` 即可。Prometheus 默认抓取
`host.docker.internal:8080/actuator/prometheus`。
