const LEGACY_KEY = 'fulfillment.checkoutKey'
const INTENT_PREFIX = 'fulfillment.checkoutIntent.'

function recoveryError(message) {
  const error = new Error(message)
  error.code = 'CHECKOUT_RECOVERY_REQUIRED'
  return error
}

function accountKey(userId) {
  if (userId === null || userId === undefined || String(userId).trim() === '') {
    throw recoveryError('登录身份已变化，请重新登录后核对原结算')
  }
  return `${INTENT_PREFIX}${userId}`
}

/**
 * Same-origin tabs serialize creation/rotation with Web Locks, not a localStorage read/write race.
 * No unlocked fallback: an unsupported/insecure browser must not silently weaken idempotency.
 */
async function withIntentLock(userId, action) {
  const storageKey = accountKey(userId)
  if (!globalThis.navigator?.locks?.request) {
    throw recoveryError('当前浏览器不支持安全保存结算凭证，请使用 localhost 或 HTTPS 下的现代浏览器')
  }
  return globalThis.navigator.locks.request(storageKey, () => action(storageKey))
}

function newKey() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

function parseRecord(raw) {
  try {
    const value = JSON.parse(raw)
    if (!value || typeof value !== 'object'
        || (value.key !== null && (typeof value.key !== 'string' || !value.key.trim()))
        || (value.amount !== null && (typeof value.amount !== 'number'
          || !Number.isFinite(value.amount) || value.amount < 0))) {
      throw new Error('invalid')
    }
    return value
  } catch {
    // Do not erase possibly unresolved intent on corruption and generate a second purchase.
    throw recoveryError('本地结算凭证需要核对，请保留浏览器数据并先查看原订单')
  }
}

/**
 * Keep the old global value for other accounts. A user's old amount record is the best available
 * association, even if another account already rotated the global key. No old identity is guessed.
 * The scoped null-key tombstone prevents completed legacy keys from being imported again.
 */
function readOrMigrate(storageKey, userId) {
  const current = localStorage.getItem(storageKey)
  if (current !== null) return parseRecord(current)

  const amountRaw = localStorage.getItem(`${LEGACY_KEY}.amount.${userId}`)
  const oldAmount = amountRaw === null ? null : parseRecord(amountRaw)
  const legacyKey = oldAmount?.key || localStorage.getItem(LEGACY_KEY)
  const record = { key: legacyKey || null, amount: oldAmount?.amount ?? null }
  localStorage.setItem(storageKey, JSON.stringify(record))
  return record
}

/** A scoped key survives logout/reload; a completed intent leaves a migration tombstone. */
export async function currentCheckoutKey(userId) {
  return withIntentLock(userId, storageKey => {
    const intent = readOrMigrate(storageKey, userId)
    if (!intent.key) {
      intent.key = newKey()
      intent.amount = null
      localStorage.setItem(storageKey, JSON.stringify(intent))
    }
    return intent.key
  })
}

/** Only the response for this exact active key may complete it; late responses are harmless. */
export async function rotateCheckoutKey(userId, expectedKey, stillActive = () => true) {
  return withIntentLock(userId, storageKey => {
    if (!stillActive()) return false
    const raw = localStorage.getItem(storageKey)
    if (raw === null || !expectedKey || parseRecord(raw).key !== expectedKey) return false
    localStorage.setItem(storageKey, JSON.stringify({ key: null, amount: null }))
    return true
  })
}

/** Freeze the original agreed amount, using the same lock and record as the user's retry key. */
export async function checkoutExpectedAmount(key, currentAmount, userId) {
  return withIntentLock(userId, storageKey => {
    const intent = readOrMigrate(storageKey, userId)
    if (!key || intent.key !== key) {
      throw recoveryError('另一页面已变更结算凭证，请先核对原订单后刷新')
    }
    if (intent.amount !== null) return intent.amount
    if (typeof currentAmount !== 'number' || !Number.isFinite(currentAmount) || currentAmount < 0) {
      throw recoveryError('结算金额不可用，请先刷新购物车')
    }
    intent.amount = currentAmount
    localStorage.setItem(storageKey, JSON.stringify(intent))
    return currentAmount
  })
}

/** A known pre-intent rejection may refresh ONLY the amount belonging to its original key. */
export async function forgetCheckoutAmount(userId, expectedKey, stillActive = () => true) {
  return withIntentLock(userId, storageKey => {
    if (!stillActive()) return false
    const raw = localStorage.getItem(storageKey)
    if (raw === null) return false
    const intent = parseRecord(raw)
    if (!expectedKey || intent.key !== expectedKey) return false
    intent.amount = null
    localStorage.setItem(storageKey, JSON.stringify(intent))
    return true
  })
}
