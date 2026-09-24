# 履约商城前端

Vue 3 + vue-router 4 + Vite 5。没有 Pinia，没有 axios，没有 UI 组件库——
这一层要展示的是「和后端的并发语义怎么对接」，不是 npm 装包能力。

## 跑起来

后端先起：MySQL、Redis、gateway(18080)、commerce-service(18084)、
order-service(18081)、inventory-service(18082)。

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:5173
```

开发服务器把 `/api` 代理到 `http://localhost:18080`。Gateway 不在这个端口的话，
复制 `.env.example` 为 `.env.local` 改掉 `VITE_GATEWAY_URL`。

生产构建 `pnpm build`，产物在 `dist/`，用 `nginx.conf` 做静态托管 + 反向代理。

依赖版本固定在 `pnpm-lock.yaml`。`node_modules` 和 `dist` 是本地生成物。

## 目录

```
src/
  api/
    http.js       fetch 封装：带令牌、统一错误形状、401 处理
    index.js      所有后端 URL 只在这里出现一次
    checkout.js   结算幂等键的生命周期
  stores/session.js   登录态
  router/index.js     路由 + 前置守卫
  utils/format.js     金额/时间/状态的展示转换
  views/              六个页面
```

## 五个页面

| 路由 | 页面 | 要登录 |
| --- | --- | --- |
| `/products` | 商品列表：搜索、分类筛选、分页 | 否 |
| `/products/:spuId` | 商品详情：选规格、加购物车 | 加购时要 |
| `/cart` | 购物车：改数量、勾选、结算 | 是 |
| `/orders` | 我的订单 | 是 |
| `/orders/:orderId` | 订单详情：状态、预占状态、商品快照 | 是 |
| `/login` | 登录 / 注册 | — |

## 四件想清楚了才写的事

**1. 路由守卫不是权限控制。** `meta.requiresAuth` 挡的是「没登录点进购物车看到一片报错」。
它挡不住任何攻击——改掉 meta、或者直接在控制台发请求都能绕过。
真正的鉴权只发生在 Gateway 验签那一步。面试问「前端怎么做权限」，
第一句要说的是把「菜单/路由是展示」和「接口鉴权是安全」分开。

**2. 幂等键由前端生成，失败重试复用，成功之后才换。**
因为只有前端知道「双击了两次」「超时后又点了一次」是同一次用户操作。
键存在 localStorage 而不是组件内存里，正是为了覆盖「提交到一半页面被刷新了」
这个最需要幂等的时刻。换早了会重复下单，不换会让下一笔真新单被判成重放。
细则写在 `api/checkout.js`，分支逻辑写在 `views/CartView.vue` 的 `handleCheckoutError`。

**3. 前端不做金额运算。** 结算传的 `expectedAmount` 是后端算出来的 `cart.selectedAmount`，
不是前端把 price×quantity 加出来的。JS 的浮点加法会让价格根本没变的情况下
也被判成 `PRICE_CHANGED`。前端只负责显示金额。

**4. 库存 null 是「不知道」，不是 0。** Inventory 查不到时 `availableStock` 是 null。
显示成「售罄」是把一个只读依赖的抖动说成了商品没货——全站商品会一起变成不可买。
所以 null 显示「库存未知」，按钮也不禁用，真没货交给下单时的预占去拒绝。

## 已知的欠账

- **令牌放 localStorage**，XSS 可读。正解是 `HttpOnly; Secure; SameSite=Lax` Cookie +
  CSRF 防护。这是知道风险后的取舍，不是「没问题」。
- **没有令牌续期。** ttl 7200 秒，到点就被踢回登录页，正在下单也一样。
- **两套错误体。** commerce 返回 `{code, message}`，order-service 用 Spring 的
  ProblemDetail `{title, status, detail}`。`http.js` 里兼容了两种——
  这是把后端的不一致转嫁给前端，正确的修法是在 Gateway 或 order-service 统一。
- **两套分页约定。** 商品列表 page 从 1 开始，订单列表从 0 开始。
  差异挡在 `api/index.js`，但它是一个真实的接口缺陷。
- **没有支付页。** 支付回调走 payment-service 的 `/api/payments/callbacks/**`，
  目前只能用接口模拟。

## 结算链路

商品浏览、注册登录、加购物车、结算、同键重放、订单查询和模拟支付回调已通过本机 HTTP 冒烟。
详见 `../docs/mvp-v2-test-evidence-2026-09-24.md`。
