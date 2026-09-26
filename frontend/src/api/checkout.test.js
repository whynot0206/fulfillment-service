import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { currentCheckoutKey, rotateCheckoutKey, checkoutExpectedAmount, forgetCheckoutAmount } from './checkout.js'

let items
beforeEach(() => {
  items = new Map()
  globalThis.localStorage = {
    getItem: key => items.get(key) ?? null,
    setItem: (key, value) => items.set(key, String(value)),
    removeItem: key => items.delete(key)
  }
  // Faithful exclusion contract for the real module's Web Locks calls; no browser/network needed.
  const queues = new Map()
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: {
    request: (name, action) => {
      const next = (queues.get(name) ?? Promise.resolve()).catch(() => {}).then(action)
      queues.set(name, next)
      return next
    }
  } } })
})

test('unknown retry keeps original key and amount after account switches or page reconstruction', async () => {
  const a = await currentCheckoutKey(1)
  assert.equal(await checkoutExpectedAmount(a, 399, 1), 399)
  const b = await currentCheckoutKey(2)
  assert.notEqual(a, b)
  assert.equal(await checkoutExpectedAmount(b, 798, 2), 798)
  assert.equal(await rotateCheckoutKey(2, b), true)
  assert.equal(await currentCheckoutKey(1), a)
  assert.equal(await checkoutExpectedAmount(a, 459, 1), 399)
})

test('a terminal intent permits a fresh key and new agreed amount for the same user', async () => {
  const old = await currentCheckoutKey(1)
  await checkoutExpectedAmount(old, 399, 1)
  assert.equal(await rotateCheckoutKey(1, old), true)
  const next = await currentCheckoutKey(1)
  assert.notEqual(next, old)
  assert.equal(await checkoutExpectedAmount(next, 798, 1), 798)
})

test('late old response cannot delete a newer same-account key or amount', async () => {
  const old = await currentCheckoutKey(1)
  await rotateCheckoutKey(1, old)
  const next = await currentCheckoutKey(1)
  await checkoutExpectedAmount(next, 798, 1)
  assert.equal(await rotateCheckoutKey(1, old), false)
  assert.equal(await forgetCheckoutAmount(1, old), false)
  assert.equal(await currentCheckoutKey(1), next)
  assert.equal(await checkoutExpectedAmount(next, 999, 1), 798)
})

test('concurrent first calls share one key under the origin-wide account lock', async () => {
  const keys = await Promise.all(Array.from({ length: 50 }, () => currentCheckoutKey(1)))
  assert.equal(new Set(keys).size, 1)
  const amounts = await Promise.all(Array.from({ length: 20 }, (_, index) =>
    checkoutExpectedAmount(keys[0], 399 + index, 1)))
  assert.deepEqual(new Set(amounts), new Set([399]))
})

test('old tab cannot overwrite a current intent with its stale key', async () => {
  const old = await currentCheckoutKey(1)
  await rotateCheckoutKey(1, old)
  const next = await currentCheckoutKey(1)
  await assert.rejects(() => checkoutExpectedAmount(old, 399, 1),
    error => error.code === 'CHECKOUT_RECOVERY_REQUIRED')
  assert.equal(await currentCheckoutKey(1), next)
})

test('legacy per-user amount association wins even if global key was rotated by another account', async () => {
  items.set('fulfillment.checkoutKey', 'user-b-key')
  items.set('fulfillment.checkoutKey.amount.1', JSON.stringify({ key: 'unknown-user-a', amount: 399 }))
  items.set('fulfillment.checkoutKey.amount.2', JSON.stringify({ key: 'user-b-key', amount: 798 }))
  assert.equal(await currentCheckoutKey(1), 'unknown-user-a')
  assert.equal(await checkoutExpectedAmount('unknown-user-a', 459, 1), 399)
  assert.equal(await currentCheckoutKey(2), 'user-b-key')
  await rotateCheckoutKey(2, 'user-b-key')
  assert.equal(await currentCheckoutKey(1), 'unknown-user-a')
  assert.equal(items.get('fulfillment.checkoutKey'), 'user-b-key')
})

test('legacy global-only key is preserved and completion tombstone prevents repeated migration', async () => {
  items.set('fulfillment.checkoutKey', 'legacy-unknown')
  assert.equal(await currentCheckoutKey(1), 'legacy-unknown')
  await rotateCheckoutKey(1, 'legacy-unknown')
  assert.notEqual(await currentCheckoutKey(1), 'legacy-unknown')
  assert.equal(items.get('fulfillment.checkoutKey'), 'legacy-unknown')
  assert.equal(await currentCheckoutKey(2), 'legacy-unknown')
})

test('logout or changed login context suppresses even a queued success rotation', async () => {
  const key = await currentCheckoutKey(1)
  let release
  let entered
  const hasEntered = new Promise(resolve => { entered = resolve })
  const blocker = navigator.locks.request('fulfillment.checkoutIntent.1', async () => {
    entered()
    await new Promise(resolve => { release = resolve })
  })
  await hasEntered
  let active = true
  const rotation = rotateCheckoutKey(1, key, () => active)
  active = false
  release()
  await blocker
  assert.equal(await rotation, false)
  assert.equal(await currentCheckoutKey(1), key)
})

test('pre-intent rejection refreshes only the same active key amount', async () => {
  const key = await currentCheckoutKey(1)
  await checkoutExpectedAmount(key, 399, 1)
  assert.equal(await forgetCheckoutAmount(1, key), true)
  assert.equal(await checkoutExpectedAmount(key, 459, 1), 459)
  assert.equal(await forgetCheckoutAmount(1, key, () => false), false)
  assert.equal(await checkoutExpectedAmount(key, 499, 1), 459)
})

test('corrupt stored intent fails closed without erasing evidence or replacing its key', async () => {
  items.set('fulfillment.checkoutIntent.1', '{broken')
  await assert.rejects(() => currentCheckoutKey(1), error => error.code === 'CHECKOUT_RECOVERY_REQUIRED')
  assert.equal(items.get('fulfillment.checkoutIntent.1'), '{broken')
})

test('unsupported browser fails closed instead of using unlocked localStorage', async () => {
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} })
  await assert.rejects(() => currentCheckoutKey(1), error => error.code === 'CHECKOUT_RECOVERY_REQUIRED')
  assert.equal(items.size, 0)
})
