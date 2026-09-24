const STORAGE_KEY = 'fulfillment.checkoutKey'

/**
 * 结算幂等键的生命周期。
 *
 * <p>这个文件很短，但它是整个前端里唯一和后端并发语义直接对接的地方，
 * 三条规则都要能讲清楚为什么：</p>
 *
 * <ol>
 *   <li><b>键由前端生成。</b>幂等要挡的是「同一次用户操作被发了多次」——双击、
 *       超时后手动重试、断网重连。只有前端知道这三次请求是同一次操作。
 *       如果让后端生成，每个到达的请求都会拿到一个新键，等于没做幂等。</li>
 *   <li><b>失败重试要复用同一个键。</b>这是幂等的全部意义。上一次提交可能已经
 *       在后端建好了订单，只是响应丢了；带着同一个键重试，后端会认出这是重放，
 *       把上次的结果还给你，而不是再建一张订单。换个新键就等于说
 *       「这是一次新的下单」，于是用户收到两张订单。</li>
 *   <li><b>只在下单成功后换新键。</b>换早了会重复下单，不换会导致下一次真正的
 *       新订单被误判成重放（同键异载荷 → 409 IDEMPOTENCY_KEY_REUSED）。</li>
 * </ol>
 *
 * <p>存在 localStorage 而不是组件内存里，是为了覆盖「提交时页面崩了/被刷新了」
 * 这种情况——那正是最需要幂等的时刻。放在组件 state 里，刷新一次键就丢了。</p>
 */

function newKey() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  // 兜底：randomUUID 需要安全上下文（https 或 localhost）。
  // 这里的随机性只用于「区分不同的用户操作」，不用于安全，所以够用。
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/** 拿到当前这次结算用的键；没有就生成一个并记下来。 */
export function currentCheckoutKey() {
  let key = localStorage.getItem(STORAGE_KEY)
  if (!key) {
    key = newKey()
    localStorage.setItem(STORAGE_KEY, key)
  }
  return key
}

/** 下单成功后调用。下一次结算会拿到一个新键。 */
export function rotateCheckoutKey() {
  localStorage.removeItem(STORAGE_KEY)
}
