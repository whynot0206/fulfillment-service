import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { request, setUnauthorizedHandler } from './http.js'
import { currentToken, signIn, signOut } from '../stores/session.js'

let unauthorized
beforeEach(() => {
  const items = new Map()
  globalThis.localStorage = { getItem: key => items.get(key) ?? null,
    setItem: (key, value) => items.set(key, String(value)), removeItem: key => items.delete(key) }
  signOut()
  unauthorized = 0
  setUnauthorizedHandler(() => { unauthorized++ })
})

function pendingUnauthorized() {
  let release
  globalThis.fetch = () => new Promise(resolve => { release = resolve })
  return () => release(new Response(JSON.stringify({ code: 'TOKEN_INVALID', message: 'expired' }),
    { status: 401, headers: { 'Content-Type': 'application/json' } }))
}

test('late 401 from signed-out account cannot erase newer account login', async () => {
  signIn({ token: 'synthetic-token-a', userId: 1, username: 'a' })
  const release = pendingUnauthorized()
  const oldRequest = request('/checkout', { method: 'POST', body: { expectedAmount: 399 } })
  signOut()
  signIn({ token: 'synthetic-token-b', userId: 2, username: 'b' })
  release()
  await assert.rejects(oldRequest, error => error.status === 401)
  assert.equal(currentToken(), 'synthetic-token-b')
  assert.equal(unauthorized, 0)
})

test('late 401 does not erase a newer login to the same account', async () => {
  signIn({ token: 'synthetic-old-token', userId: 1, username: 'a' })
  const release = pendingUnauthorized()
  const oldRequest = request('/cart')
  signIn({ token: 'synthetic-new-token', userId: 1, username: 'a' })
  release()
  await assert.rejects(oldRequest)
  assert.equal(currentToken(), 'synthetic-new-token')
  assert.equal(unauthorized, 0)
})

test('401 for the still-current protected token signs out and navigates once', async () => {
  signIn({ token: 'synthetic-token-a', userId: 1, username: 'a' })
  const release = pendingUnauthorized()
  const currentRequest = request('/cart')
  release()
  await assert.rejects(currentRequest)
  assert.equal(currentToken(), null)
  assert.equal(unauthorized, 1)
})

test('anonymous login rejection never signs out an already active session', async () => {
  signIn({ token: 'synthetic-token-a', userId: 1, username: 'a' })
  const release = pendingUnauthorized()
  const login = request('/auth/login', { method: 'POST', anonymous: true, body: {} })
  release()
  await assert.rejects(login)
  assert.equal(currentToken(), 'synthetic-token-a')
  assert.equal(unauthorized, 0)
})
