import { http } from './http'

/**
 * 后端接口的唯一出口。
 *
 * <p>组件里不允许出现字符串路径——所有 URL 只在这个文件里写一次。
 * 后端改了路径只需要改这里，而不是全局搜 '/api/cart'。</p>
 */

// ——认证——

export function register(username, password) {
  return http.post('/auth/register', { username, password }, { anonymous: true })
}

export function login(username, password) {
  return http.post('/auth/login', { username, password }, { anonymous: true })
}

export function me() {
  return http.get('/auth/me')
}

// ——商品（匿名可访问）——

/** 注意：商品分页 page 从 1 开始。 */
export function listProducts({ page = 1, size = 12, keyword = '', categoryId = null } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (keyword) {
    params.set('keyword', keyword)
  }
  if (categoryId) {
    params.set('categoryId', String(categoryId))
  }
  return http.get(`/products?${params}`, { anonymous: true })
}

export function listCategories() {
  return http.get('/products/categories', { anonymous: true })
}

export function productDetail(spuId) {
  return http.get(`/products/${spuId}`, { anonymous: true })
}

// ——购物车（需登录）——

export function viewCart() {
  return http.get('/cart')
}

export function addToCart(skuId, quantity) {
  return http.post('/cart/items', { skuId, quantity })
}

export function updateCartQuantity(skuId, quantity) {
  return http.put(`/cart/items/${skuId}`, { quantity })
}

export function updateCartSelected(skuId, selected) {
  return http.put(`/cart/items/${skuId}/selected?selected=${selected}`, undefined)
}

export function removeCartItem(skuId) {
  return http.del(`/cart/items/${skuId}`)
}

// ——结算——

/**
 * 提交结算。
 *
 * @param idempotencyKey 由调用方负责生成和复用，见 checkout.js 里的说明
 * @param expectedAmount 用户屏幕上看到的应付金额，**必须**是后端算出来的那个数
 *                       （cart.selectedAmount），不是前端自己乘出来的。
 *                       前端做浮点乘法会得到 0.30000000000000004 这种值，
 *                       然后被后端判成价格不符——而真正的价格并没有变。
 */
export function submitCheckout(idempotencyKey, expectedAmount) {
  return http.post('/checkout', { expectedAmount }, {
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

// ——订单（需登录）——

/**
 * 我的订单。
 *
 * <p><b>这里的 page 从 0 开始，和商品列表的 1 不一样。</b>order-service 的读接口
 * 是后加的，用了 Spring 的默认习惯；commerce 的商品列表用的是从 1 开始。
 * 这是一个真实存在的接口不一致，正确的修法是统一成一种并写进 API 约定。
 * 现在把差异挡在这一层，不让它渗进组件。</p>
 */
export function listOrders({ page = 0, size = 10 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return http.get(`/orders?${params}`)
}

export function orderDetail(orderId) {
  return http.get(`/orders/${orderId}`)
}

export function cancelOrder(orderId) {
  return http.post(`/orders/${orderId}/cancel`)
}

export function mockPay(orderId) {
  return http.post(`/payments/orders/${orderId}/mock-success`)
}
