# MVP V2 启动与验证手册（commerce-service + 前端）

日期：2026-09-24
范围：本轮新增 `microservices/commerce-service`、`frontend/`，改动 gateway 与 order-service。

---

## 0. 先说清楚这份文档的前提

2026-09-24 已在本机 JDK 17 上完成微服务 Reactor 测试、前端生产构建，以及
Gateway -> Commerce -> Order -> Inventory -> Payment 的真实 HTTP 冒烟。
结算核心方法已实现；测试与修复证据见 `mvp-v2-test-evidence-2026-09-24.md`。

---

## 1. 一次性准备：环境变量

commerce-service 和 gateway 都做了「缺关键配置就启动失败」的处理，这是故意的：
宁可起不来，也不要用内置弱密钥跑起来，然后在上线时才发现所有人的令牌都能互相伪造。

| 变量 | 谁要用 | 缺了会怎样 |
| --- | --- | --- |
| `AUTH_JWT_SECRET` | commerce-service、gateway | 启动即 `IllegalStateException`，要求至少 32 字符 |
| `AUTH_JWT_ISSUER` | commerce-service、gateway | 有默认值 `fulfillment-commerce`，两边必须一致 |
| `INTERNAL_SERVICE_TOKEN` | commerce/order/payment/inventory | Feign 拦截器启动时报 `must be configured` |
| `COMMERCE_DB_PASSWORD` | commerce-service | 连不上库 |
| `COMMERCE_NODE_ID` | commerce-service | 默认 0；**多实例必须每个实例不同**，配重了会发重复订单号 |

`AUTH_JWT_SECRET` 在 gateway 和 commerce-service 之间必须**取同一个值**，否则
网关验不过自己这套体系签发的令牌，你会看到所有需要登录的接口都 401，而登录接口本身正常。
这是最容易浪费半小时的一个坑。

PowerShell 里生成一个够长的随机密钥并设进当前会话：

```powershell
$bytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($bytes)

$bytes2 = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes2)
$env:INTERNAL_SERVICE_TOKEN = [Convert]::ToBase64String($bytes2)

$env:COMMERCE_DB_PASSWORD = '<你的库口令>'
```

注意 `$env:` 只对当前这个 PowerShell 窗口有效。四个服务如果开在四个窗口里，
每个窗口都要设一遍，或者用 `[Environment]::SetEnvironmentVariable(...,'User')`
写进用户环境变量。**不要写进任何进仓库的文件。**

---

## 2. 数据库

新增 schema `fulfillment_commerce`，按顺序执行：

```powershell
cd D:\vibecoding\fulfillment-service
mysql -u root -p -e "source D:/vibecoding/fulfillment-service/microservices/sql/migration-v2-commerce.sql"
mysql -u root -p -e "source D:/vibecoding/fulfillment-service/microservices/sql/migration-v2-order-snapshot.sql"
mysql --default-character-set=utf8mb4 -u root -p -e "source D:/vibecoding/fulfillment-service/microservices/sql/seed-v2-commerce-demo.sql"
```

两点要留意：

- Windows 客户端执行包含中文的种子 SQL 时需指定 `--default-character-set=utf8mb4`，
  否则可能出现 `Incorrect string value`。

- `migration-v2-order-snapshot.sql` 给 `fulfillment_order.order_item` 加了
  `name_snapshot` / `spec_snapshot` 两列。**不跑它，order-service 起来之后
  插订单明细会报 Unknown column**，而 commerce 的商品页一切正常——错误会出现在
  离改动最远的地方。
- 种子数据里的 SKU 编号 1001-1005 和 `fulfillment_inventory.sku_stock` 是
  一一对应的。如果 `sku_stock` 里没有这几行，商品页看得到、加购也正常，
  但下单会在库存预占阶段被拒。先确认：

```sql
SELECT sku_id, total_stock, reserved_stock FROM fulfillment_inventory.sku_stock;
```

种子数据里**没有任何演示用户**，密码摘要属于凭证，不进仓库。起来之后自己注册。

---

## 3. 编译与单测

仓库里没有 mvnw，用系统的 `mvn`（确认 `mvn -v` 显示的 JDK 是 17）。

```powershell
cd D:\vibecoding\fulfillment-service\microservices

# 先只编译，最快暴露语法和 import 错误
mvn -q -DskipTests clean install

# 再跑全量测试
mvn -q clean test
```

`mvn -q clean test` 应当通过。Commerce 的结算测试覆盖价格快照、库存未知、
幂等重放和下游结果未知；本机验证数据见测试证据文档。

只跑 commerce-service：

```powershell
mvn -q -pl commerce-service -am clean test
```

`-am` 是必须的，因为 commerce-service 依赖 `fulfillment-api`，不带 `-am`
会说找不到 `com.why.fulfillment.api.order.OrderClient`。

---

## 4. 启动顺序

四个窗口，顺序有讲究：被依赖的先起。

```powershell
cd D:\vibecoding\fulfillment-service\microservices

# 窗口 1
mvn -q -pl inventory-service spring-boot:run     # 18082
# 窗口 2
mvn -q -pl order-service spring-boot:run         # 18081
# 窗口 3
mvn -q -pl commerce-service spring-boot:run      # 18084
# 窗口 4
mvn -q -pl gateway spring-boot:run               # 18080
# 窗口 5
mvn -q -pl payment-service spring-boot:run       # 18083
```

模拟支付需要 payment-service（18083）与其余四个进程同时运行。

健康检查：

```powershell
'18080','18081','18082','18083','18084' | ForEach-Object {
    "$_ -> " + (Invoke-RestMethod "http://localhost:$_/actuator/health").status
}
```

---

## 5. 冒烟验证（不经过前端）

用 PowerShell 直接打 Gateway，把后端单独验通，再去看前端。这样出问题时能立刻
分清是后端还是前端。

```powershell
$base = 'http://localhost:18080'

# 5.1 匿名逛商品——不带任何令牌，应该 200
$page = Invoke-RestMethod "$base/api/products?page=1&size=12"
$page.total
$page.items | Select-Object spuId, name, minPrice

# 5.2 商品详情，看 availableStock 是不是真的查到了库存
(Invoke-RestMethod "$base/api/products/1").skus |
    Select-Object skuId, price, onSale, availableStock

# 5.3 注册（201）。用户名 3-32 位 [A-Za-z0-9_-]，密码 8-72 位
$auth = Invoke-RestMethod -Method Post "$base/api/auth/register" `
    -ContentType 'application/json' `
    -Body '{"username":"why_demo","password":"demo-password-1"}'
$h = @{ Authorization = "Bearer $($auth.token)" }
$auth.userId

# 5.4 不带令牌访问购物车，**必须** 401。这一条才是鉴权生效的证据
try { Invoke-RestMethod "$base/api/cart" } catch { $_.Exception.Response.StatusCode }

# 5.5 加购物车
Invoke-RestMethod -Method Post "$base/api/cart/items" -Headers $h `
    -ContentType 'application/json' -Body '{"skuId":1001,"quantity":2}'

# 5.6 看购物车。selectedAmount 是后端算的，下一步要原样传回去
$cart = Invoke-RestMethod "$base/api/cart" -Headers $h
$cart.selectedCount; $cart.selectedAmount

# 5.7 伪造身份：自己塞一个 X-User-Id，看能不能看到别人的购物车。
#     Gateway 会在验签之前无条件剥离这个头，所以结果应该还是你自己的车
$forged = $h.Clone(); $forged['X-User-Id'] = '999999'
(Invoke-RestMethod "$base/api/cart" -Headers $forged).items.Count

# 5.8 结算。Idempotency-Key 由调用方生成，expectedAmount 传 5.6 拿到的那个数
$key = [guid]::NewGuid().ToString()
$body = @{ expectedAmount = $cart.selectedAmount } | ConvertTo-Json
$order = Invoke-RestMethod -Method Post "$base/api/checkout" `
    -Headers ($h + @{ 'Idempotency-Key' = $key }) `
    -ContentType 'application/json' -Body $body
$order

# 5.9 幂等：同键同载荷再发一次，应该拿到同一个 orderId 且 replayed=true
Invoke-RestMethod -Method Post "$base/api/checkout" `
    -Headers ($h + @{ 'Idempotency-Key' = $key }) `
    -ContentType 'application/json' -Body $body

# 5.10 我的订单（注意 page 从 0 开始）
(Invoke-RestMethod "$base/api/orders?page=0&size=10" -Headers $h).orders |
    Select-Object orderId, totalAmount, status, reservationStatus

# 5.11 对本人待支付订单执行本地模拟支付；或调用 /api/orders/{id}/cancel 取消。
# 两种操作应分别使用不同的新订单，支付与取消都会改变订单终态。
$result = Invoke-RestMethod -Method Post "$base/api/payments/orders/$($order.orderId)/mock-success" -Headers $h
$result.status
```

第 5.1 到 5.10 步已在本机通过；第 5.4 和 5.7 是安全相关的检查。

第 5.9 步是整条链路里最值得自己盯一眼的：两次响应的 `orderId` 必须一样，
第二次的 `replayed` 必须是 `true`。如果第二次给了一个新的 `orderId`，
说明幂等键根本没生效——而这种 bug 在单次手工测试里是完全看不出来的。

---

## 6. 前端

```powershell
cd D:\vibecoding\fulfillment-service\frontend
pnpm install --frozen-lockfile
pnpm dev
```

打开 http://localhost:5173 。开发服务器把 `/api` 代理到 18080，所以前端代码里
只有相对路径，不需要配后端地址，也没有 CORS。

本次使用 pnpm 安装并生成 `frontend/pnpm-lock.yaml`；后续建议执行 `pnpm install --frozen-lockfile`。
如改用 npm，应保持一份锁文件口径，避免混用导致依赖版本漂移。

前端五个页面：商品列表 `/products`、详情 `/products/:spuId`、购物车 `/cart`、
订单列表 `/orders`、订单详情 `/orders/:orderId`，外加 `/login`。
设计取舍和已知欠账写在 `frontend/README.md`，不在这里重复。

---

## 7. 已完成的三个结算方法

`microservices/commerce-service/src/main/java/com/why/fulfillment/commerce/checkout/service/CheckoutService.java`

| 方法 | 要挡住的失败 |
| --- | --- |
| `revalidate` | 购物车里的价格是渲染时的快照，不能拿来下单；库存 `null` 是"不知道"不是 0 |
| `digest` | 摘要不归一化（排序、`setScale(2)`、字段分隔符）会让重放判定时好时坏 |
| `claimIdempotencyKey` | 先查再插入是 check-then-act，并发下两个请求都会觉得自己是第一个 |

三个方法均已实现并通过对应测试。首次成功会清空购物车，因此重放还需从
`checkout_request.total_amount` 读取服务端金额；这一分支也有测试覆盖。

每个方法上面的 javadoc 里都有一个**没有唯一答案的开放问题**（`revalidate` 的
报错顺序、status=0 记录该返回什么），那些是留给你自己判断的，不是漏写。

---

## 8. 一致性自查结果

以下是设计自查；实际编译、自动化测试和 HTTP 验收结果见测试证据文档。

**确认符合 AGENTS.md 约定：**

- commerce-service 全模块**零 Lombok**，`grep -i lombok` 无命中。
- **零 `@Autowired`**，全部构造器注入。`@Value` 只出现在构造器参数上
  （`JwtCodec:57`、`CartService:46`、`CartCache:45`、`CheckoutService:84`、
  `OrderIdGenerator:48`）。
- `application.yml` 里所有敏感值都是 `${ENV:}` 形式，且**默认值为空**：
  `COMMERCE_DB_PASSWORD`（第 7 行）、`INTERNAL_SERVICE_TOKEN`（第 32 行）、
  `AUTH_JWT_SECRET`（第 38 行）。没有任何硬编码口令或密钥。
- 日志语句共 8 处，均不含密码、令牌、Authorization、支付签名。
  `UserAccount:64` 还专门覆盖了 `toString` 防止有人 `log.info("user={}", account)`
  把密码摘要打进日志。
- commerce-service 里**没有任何自定义 Micrometer 指标**（`grep Counter|Timer|MeterRegistry`
  无命中），因此不存在把 order id / user id 当 label 的风险。
  `management.metrics.tags` 只有一个低基数的 `application`。
- commerce-service 已注册进 `microservices/pom.xml:21` 的 `<modules>`。
- Gateway 路由齐全：`/api/auth/**`、`/api/products/**`、`/api/cart/**`、
  `/api/checkout/**` 指向 18084（`application.yml:12-15`），
  `/api/orders/**` 指向 18081。
- 匿名放行清单只有五条（`application.yml:47-52`）：`/api/auth/**`、
  `/api/products/**`、`/api/inventory/**`、`/api/payments/callbacks/**`、
  `/actuator/**`。`/api/cart/**`、`/api/checkout/**`、`/api/orders/**`
  都**不在**里面，即必须带令牌。
- `X-User-Id` 的剥离在 `AuthenticationGlobalFilter:82-83` 完成，位置在
  白名单判断（第 85 行）**之前**，且是无条件的。同时一起剥掉了 `X-Username`。
  这是第 5.7 步验证的那条。
- `CheckoutService` 调用的四个 mapper 方法签名与 `CheckoutRequestMapper`
  的定义逐个对上（`insertClaim:34`、`selectByUserAndKey:42`、
  `markSubmitted:58`、`markRejected:75`）。
- 前端无任何硬编码密钥；`.env.example` 里只有一个 `VITE_GATEWAY_URL`
  和一句"VITE_ 前缀的变量会被打进产物，不能放密钥"的说明。

**本次顺手修掉的四个问题：**

- `GlobalExceptionHandler` 原来没有处理 `MissingRequestHeaderException`，
  少传 `Idempotency-Key` 会掉进 `Exception` 兜底分支变成 500。已补成 400
  `MISSING_HEADER`。客户端的错误回 500，会让人去翻服务端日志找一个不存在的 bug。
- `vite.config.js` 原来用 `process.env.VITE_GATEWAY_URL` 读代理目标。
  **Vite 不会把 `.env` 文件里的变量注入 `process.env`**，所以 `.env.local`
  里写了也不生效。已改成 `loadEnv(mode, process.cwd())`。
- `CheckoutService` 原来在 `orderClient.create` 返回**空响应体**时会
  `markRejected`。这和超时是同一类情况——请求可能已经执行完了，只是回来的东西不对，
  我们没有依据替订单服务下「没建成」的结论。已改成保持「处理中」并回
  `CHECKOUT_RESULT_UNKNOWN`，与抛异常那条分支一致，并补了一个用例
  `anEmptyResponseIsUnknownRatherThanRejected` 钉住它。
- 种子数据里 `product_image.url` 写的是 `/img/spu-1.svg` 这类路径，但前端
  没有对应文件，商品图会全部裂掉。已补 `frontend/public/img/spu-{1,2,3}.svg`。

**已通过编译确认的：**

- commerce-service 跨模块 import 的 `com.why.fulfillment.api.*` 各类
  均可编译。`OrderClient`、`OrderCreateRequest`（5 个字段）、
  `OrderCreateItem`（6 个字段，含两个 snapshot）、`OrderCreateResponse`
  我逐个核过，`CartService.selectedItems/removeCheckedOut`、
  `SkuAvailabilityService.available` 两个重载、`ProductSkuMapper.selectByIds`、
  `ProductSpuMapper.selectById` 也都对上了。但**参数顺序**这类错误阅读时最容易看漏。
- `CheckoutServiceTest` 的 mock/verify 签名已修正并通过。
- 前端六个 view 的 import 已做过一次机械比对：把 `src/` 下所有 `export`
  和所有 `import` 拉出来逐个对，**没有悬空引用**，`router/index.js` 里
  引用的六个 `.vue` 文件全部存在。剩下的风险是模板里的拼写和 Vue 的
  运行时行为。前端生产构建已通过，浏览器交互仍需人工验收。

---

## 9. JMeter 口径变更（重要）

`performance/cycle5-order-comparison.jmx` 打的是 **Gateway 的 18080 端口**
（第 37-38 行 `HTTPSampler.domain` / `HTTPSampler.port`），而
`/api/orders/**` 不在匿名放行清单里。也就是说：

> **这份压测计划现在会全量 401。** 本轮加的 `AuthenticationGlobalFilter`
> 会在它到达 order-service 之前就把它挡掉。

除此之外还有第二个问题：它的请求体（第 49 行）里 `items` 只有
`skuId` / `spuId` / `count`，**没有 `price`**。而 order-service 的
`validate()` 在本轮之前就已经要求 `price` 非空了。

两件事合起来的结论：

1. 要继续用它压测，得给 JMX 加一个先登录取令牌的前置步骤（或者临时把
   `/api/orders/**` 放进匿名清单，但那样测的就不是加了鉴权之后的系统）。
2. **加了鉴权之后测出来的数字，和历史基线不是同一个口径**，不能混在一张表里
   比较，也不能拿来直接写进简历。鉴权多了一次 HMAC 验签和一次请求头改写，
   这部分开销是新增的。要对比就重新跑一遍两端，标清楚版本。

---

## 10. 本轮没做的事

不是遗漏，是明确划在 MVP 外面的：

- **真实支付渠道及持久化支付单**尚未实现。订单详情页现在有本地模拟支付按钮；
  第三方支付回调仍走 payment-service 的 `/api/payments/callbacks/**`。
- **令牌续期**。ttl 7200 秒，到点就被踢回登录页，正在下单也一样。
- **令牌存储方式**。放在 localStorage，XSS 可读。正解是 HttpOnly Cookie +
  CSRF 防护。这是知道风险后的取舍。
- **统一错误体**。commerce 回 `{code, message}`，order-service 回 Spring
  的 ProblemDetail。前端在 `http.js` 里兼容了两种，这是把后端的不一致
  转嫁给了前端。
- **统一分页约定**。商品列表 page 从 1 开始，订单列表从 0 开始。
- **下游端口的网络隔离**。Order 的 `/api/orders/**` 已增加内部令牌校验，
  直连伪造 `X-User-Id` 已由 200 修复为 401；Commerce 自己验证 JWT。
  本地服务端口仍对本机开放，部署时需限制监听地址和网络访问。
