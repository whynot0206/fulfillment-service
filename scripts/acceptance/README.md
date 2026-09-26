# Windows 本地隔离部署与验收

这是本机五进程微服务 + Vue 开发服务器的验收环境，不是云端部署或生产高可用方案。所有新端口只监听 `127.0.0.1`，不复用/重置已有的 Windows MySQL 或其他项目的 Redis。

## 前置条件与构建

- PowerShell 7.4+、已启动的 Docker Desktop Linux 引擎。
- JDK 17、Maven 3.6.3+；本轮实测 Temurin 17.0.20.1、Maven 3.9.11。
- Node.js；本轮前端使用 Node 22.19.0、pnpm 11.19.0、仓库现有锁文件。
- 预留 13306、16379、18080–18084、5173 端口。占用时脚本拒绝启动，不杀占用进程。

在仓库根目录运行；下列工具路径是本机本轮安装位置，可按自己的安装位置替换：

```powershell
$env:JAVA_HOME = 'D:\vibecoding\.toolchains\java17\jdk-17.0.20.1+1'
& 'D:\vibecoding\.toolchains\maven\apache-maven-3.9.11\bin\mvn.cmd' -B -f microservices/pom.xml package
Push-Location frontend
pnpm install --frozen-lockfile
pnpm build
Pop-Location
```

`package` 包含测试，不跳过测试。根目录单体仍是独立实验基线，本入口不运行单体测试。

Windows 上重新打包前，若这套验收后端已在运行，先执行 `./scripts/acceptance/stop-backend.ps1`，避免运行中的 JAR 被占用导致 Spring Boot repackage 失败；该脚本只停止登记且通过身份校验的本项目进程，不删除数据。构建通过后重新运行 `start-backend.ps1`，故障测试期间需保留原来的 `-UseFaultProxies` 模式。

## 首次部署

```powershell
& ./scripts/acceptance/use-secrets.ps1 -CreateIfMissing
& ./scripts/acceptance/start-infra.ps1
& ./scripts/acceptance/initialize-db.ps1 -InitializeEmptyInstance
& ./scripts/acceptance/start-backend.ps1 `
    -JavaPath "$env:JAVA_HOME\bin\java.exe" -OrderTimeoutSeconds 180
```

依赖镜像为 `mysql:8.0.46`、`redis:7.4-alpine`，首次拉取可能较慢。初始化只允许空的专用容器，顺序为：旧基础 schema → 周期 7–11 → 拆库 → Commerce → 订单快照 → 商品种子 → 独立最小权限账号。脚本不把整个历史 SQL 目录交给容器自动执行。

三套业务 schema 为 `fulfillment_order`、`fulfillment_inventory`、`fulfillment_commerce`；每个业务账号仅访问所属 schema。旧 `fulfillment` 仅作首次迁移引导，不启动单体业务进程。

首次生成的随机密钥通过当前 Windows 用户 DPAPI 加密，保存到被 Git 忽略的 `.runtime/acceptance/fulfillment-acceptance/secrets.dpapi.json`，只允许当前用户读取。不会输出明文，不会自动轮换；后续命令需要在同一 PowerShell 进程先运行 `use-secrets.ps1`。此配置不适合直接迁移到另一台电脑或另一用户。

`180` 表示本轮演示订单三分钟未付自动取消；脚本默认仍为 1800 秒。旧实验下单入口固定关闭，消费者统一经过 Commerce 校验。Commerce 明确允许两个本机前端来源 `http://127.0.0.1:5173` 和 `http://localhost:5173`，不使用通配来源。

## 打开前端

在另一个终端进入 `frontend` 后运行：

```powershell
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
```

打开 <http://127.0.0.1:5173>，注册本地测试账号，然后从商品、购物车到订单操作。模拟支付不会实际扣款。Vite 代理 `/api` 到 Gateway；不是直接访问各个后端。

结算凭证和最初同意金额按账号保存在本机浏览器，通过 Web Locks 串行保护同源多标签页。需要支持 Web Locks 的现代浏览器及 localhost/HTTPS 安全上下文；不支持时拒绝结算，不静默退回不安全的存储竞争。切换账号或刷新不会清掉未知凭证；旧版凭证会保守迁移，因此升级后可能先返回之前的订单供核对。此保护不是跨设备幂等或生产登录态保证。

| 服务 | 本机地址 |
| --- | --- |
| Gateway | `http://127.0.0.1:18080` |
| Order | `http://127.0.0.1:18081` |
| Inventory | `http://127.0.0.1:18082` |
| Payment | `http://127.0.0.1:18083` |
| Commerce | `http://127.0.0.1:18084` |
| MySQL / Redis | `127.0.0.1:13306` / `127.0.0.1:16379` |

五个后端都有 `/actuator/health` 和 `/actuator/prometheus`。指标端点可读不等于已经部署 Prometheus/Grafana，本入口不启动监控平台。

## 回归与最终状态核验

```powershell
& ./scripts/acceptance/test-process-ownership.ps1
& ./scripts/acceptance/test-commerce-api.ps1 `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/commerce-api.json
& ./scripts/acceptance/test-concurrency.ps1 `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/concurrency.json
& ./scripts/acceptance/use-secrets.ps1
& ./scripts/acceptance/verify-state.ps1 -RequireSettled
```

API 脚本每轮创建独立合成用户和订单，不删除测试数据。成功主链回归会成交 1 件；并发冒烟只验证 10 个同幂等键请求和支付/取消竞争，不代表吞吐压测。反复运行会正常消耗种子库存，不能通过重新初始化或改数掩盖库存变化。

`verify-state.ps1` 只读专用容器，检查非负库存、锁定数量对应关系，以及五个初始 100 件种子 SKU 的 `可售 + 锁定 + 已确认 = 100`。`-RequireSettled` 还要求没有待付款订单、残留库存锁或未发送支付事件；有人正在演示时请等交易终态后再运行。它不是生产全量对账工具，也不能覆盖 Redis 快速路径一致性。

## 再次启动、修改配置与停止

```powershell
& ./scripts/acceptance/use-secrets.ps1
& ./scripts/acceptance/start-infra.ps1
& ./scripts/acceptance/initialize-db.ps1
& ./scripts/acceptance/start-backend.ps1 `
    -JavaPath "$env:JAVA_HOME\bin\java.exe" -OrderTimeoutSeconds 180

# 仅停止登记且 PID、启动时间、可执行路径匹配的本项目后端进程
& ./scripts/acceptance/stop-backend.ps1
# 或仅停止选定服务
& ./scripts/acceptance/stop-backend.ps1 -Services order-service
```

再次初始化会验证迁移指纹并复用已完成实例，不重放种子或覆盖数据。发现未知数据、半完成迁移或身份不符时会拒绝继续；不要删标记强行绕过。

## 可靠性增量升级与故障验证

新增迁移不改变历史 bootstrap 指纹。升级前先停止已登记的 Commerce/Order：

```powershell
& ./scripts/acceptance/use-secrets.ps1
& ./scripts/acceptance/stop-backend.ps1 -Services commerce-service,order-service
& ./scripts/acceptance/migrate-reliability.ps1
```

迁移逐文件记录 SHA-256 和完成状态，不重放种子；半完成时先检查，再用 `-ResumeIncomplete` 重跑同一份幂等迁移。修改已应用文件的哈希会被拒绝。初始化入口也调用这一增量步骤；待升级时若旧写进程仍运行会拒绝，不能让不认识租约字段的旧发布器与新版混跑。

受控故障只限专用本机部署，额外端口 `18881/18882`。先完成打包再执行：

```powershell
& ./scripts/acceptance/start-fault-proxies.ps1
& ./scripts/acceptance/start-backend.ps1 -JavaPath "$env:JAVA_HOME\bin\java.exe" -OrderTimeoutSeconds 180 -UseFaultProxies
& ./scripts/acceptance/test-recovery.ps1 -JavaPath "$env:JAVA_HOME\bin\java.exe" `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/recovery.json

# 验证后恢复直接调用，再停止故障代理；不删除业务数据
& ./scripts/acceptance/stop-backend.ps1 -Services commerce-service,order-service
& ./scripts/acceptance/start-backend.ps1 -JavaPath "$env:JAVA_HOME\bin\java.exe" -OrderTimeoutSeconds 180
& ./scripts/acceptance/stop-fault-proxies.ps1
& ./scripts/acceptance/verify-state.ps1 -RequireSettled
```

`test-recovery.ps1` 创建合成账号/订单，真实模拟创建响应丢失、Commerce 重启、预占前/后 Order 中断、支付确认依赖失败。会中断登记的 Order/Commerce，因此不要与手动演示同时运行。请求经代理真实转发，SQL 仅查询事实，不改库存或伪造状态。测试正常完成消耗 SKU 1003 一件，其余测试订单取消。

代理故障控制需要内部令牌，不记录业务载荷；启动/停止校验进程归属。`hold` 最多 120 秒，测试必须证明主动放行前仍在等待。测试失败保留数据和日志，并在 `finally` 尝试放行；需检查服务健康后再继续。`-UseFaultProxies` 只在新启动的进程生效，存活进程不会动态改地址。

离线辅助验证：`node --test scripts/acceptance/fault-proxy.test.mjs`、`node --test frontend/src/api/checkout.test.js` 与 `./scripts/acceptance/test-outbox-redrive-contract.ps1`。它们不代替真实故障恢复或真实死信重驱验收。

### 闭环补验（必须串行）

下面命令同样要求上述所有权检查通过、两代理处于 pass、Order/Commerce 已通过 `-UseFaultProxies` 启动。不要与手动支付或其他故障脚本并行运行：

```powershell
& ./scripts/acceptance/use-secrets.ps1
# 默认 10 次失败，完整真实退避至少 766 秒，预留 20 分钟
& ./scripts/acceptance/test-outbox-closure.ps1 -Case DeadLetter `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/f07.json
& ./scripts/acceptance/test-outbox-closure.ps1 -Case ConfirmCrash `
    -JavaPath "$env:JAVA_HOME\bin\java.exe" `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/f06.json
& ./scripts/acceptance/test-outbox-closure.ps1 -Case ReleaseFailure `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/f04.json
& ./scripts/acceptance/test-stock-contention.ps1 `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/stock-contention.json
& ./scripts/acceptance/test-security-boundaries.ps1 `
    -EvidencePath .runtime/acceptance/fulfillment-acceptance/security-boundaries.json
```

F07 可拆为 `StartDeadLetter` 和 `FinishDeadLetter`；前者故意保留确认失败代理与无凭证的状态文件，后者等待真实死信，再通过受控重驱脚本核验审计及库存终态。中途退出不能自行删状态文件或直接改业务数据；应先查看已记录状态。F06 等待真实 60 秒租约，F04 验证取消后的依赖恢复；两者不得在 F07 未完成时切换代理。

库存竞争测试要求 SKU 1002 可售 100、锁定 0；通过真实订单先预占 90，再由 30 个独立账号争抢剩余 10。清理只取消本批订单，不改数；重复运行会保留历史记录。两 SKU 回滚用有界故障窗口把“预检查有货”变成“实际预占时售罄”，窗口错过要判失败，不能算售罄成功；反向购物车输入不等于数据库反向加锁实验。安全脚本要求 F07 已完成；成功会成交 SKU 1003 一件，且不会输出签名密钥或 JWT。

新增离线入口：`node --test frontend/src/api/checkout.test.js frontend/src/api/http.test.js`、`test-state-invariants.ps1`、`test-outbox-closure-contract.ps1`、`test-security-boundaries-contract.ps1`。状态核验接受明确拒绝导致的 CANCELED/FAILED 无占用终态，但待补偿状态或残留锁仍失败。

存活进程会复用：修改环境参数、订单超时或重新打包同路径 JAR 后，必须先停止对应已登记服务，再启动才能生效。`backend-processes.json` 和日志位于 `.runtime/acceptance/fulfillment-acceptance/`。停止脚本保留数据库、Redis 容器、数据卷和测试记录；没有清库/删卷快捷命令。

## 支付事件租约与死信运维

R05 的兼容迁移、停止旧发布器要求、默认只读的单事件重驱入口和审计边界见 [Outbox 运维说明](outbox-maintenance.md)。`test-outbox-redrive-contract.ps1` 只执行离线 SQL 构造断言；脚本或测试存在不代表真实 MySQL 租约竞争/重驱已验收。禁止通过消费者接口、清库或直接改库存处理死信。

## 能力边界

本次只验证单机单实例、模拟支付、本机浏览器和有限并发正确性。结果未知恢复、普通 `RESERVING` 预占前/后中断与支付确认失败重试已完成专项验证，见 [可靠性记录](../../docs/reliability-recovery-2026-09-26.md)；后续默认退避至真实死信并重驱、确认后发布器中断及账号切换等见 [闭环补验](../../docs/closure-acceptance-2026-09-26.md)。Redis 快速实验链路与普通商城路径不能默认混用同一 SKU；Redis 独立专项、多实例及真实支付等仍未在本轮验收，不得因为商城能下单就宣称生产可用。
