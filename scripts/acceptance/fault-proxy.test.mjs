import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

test('fault proxy preserves raw IDs, rejects control without token, drops only response and releases late traffic', { timeout: 15000 }, async () => {
  const fetch = (url, options = {}) => globalThis.fetch(url, { ...options, signal: AbortSignal.timeout(5000) })
  let forwarded = 0
  const origin = http.createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    forwarded++
    res.end(Buffer.concat(chunks))
  })
  await new Promise(resolve => origin.listen(0, '127.0.0.1', resolve))
  const portProbe = http.createServer()
  await new Promise(resolve => portProbe.listen(0, '127.0.0.1', resolve))
  const port = portProbe.address().port
  await new Promise(resolve => portProbe.close(resolve))
  const token = randomBytes(24).toString('hex')
  const child = spawn(process.execPath, [fileURLToPath(new URL('./fault-proxy.mjs', import.meta.url))], {
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NODE_OPTIONS: '', FAULT_PROXY_PORT: String(port),
      FAULT_PROXY_UPSTREAM: `http://127.0.0.1:${origin.address().port}`, FAULT_PROXY_TOKEN: token }
  })
  const base = `http://127.0.0.1:${port}`
  const fault = (mode, path = '/internal/orders', remaining = 1) => fetch(`${base}/__fault__/state`, {
    method: 'POST', headers: { 'X-Fault-Token': token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, path, remaining })
  })
  const payload = '{"orderId":900001791234567890}'
  const send = () => fetch(`${base}/internal/orders`, { method: 'POST', body: payload })
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Proxy startup deadline')), 5000)
      child.stdout.once('data', () => { clearTimeout(timer); resolve() })
      child.once('exit', () => { clearTimeout(timer); reject(new Error('Proxy exited before ready')) })
    })
    assert.equal((await fetch(`${base}/__fault__/state`)).status, 403)
    assert.equal(await (await send()).text(), payload)
    assert.equal((await fault('reject', '/not-an-allowed-fault')).status, 400)
    await fault('drop-response')
    await assert.rejects(send())
    assert.equal(forwarded, 2, 'drop must forward request before losing response')
    await fault('hold-before')
    const pending = send()
    let observedHeld = false
    for (let i = 0; i < 50; i++) {
      const state = await (await fetch(`${base}/__fault__/state`, { headers: { 'X-Fault-Token': token } })).json()
      if (state.held === 1) { observedHeld = true; break }
      await new Promise(resolve => setTimeout(resolve, 20))
    }
    assert.equal(observedHeld, true, 'must observe held request before releasing')
    assert.equal(forwarded, 2)
    await fault('pass', '', 0)
    assert.equal(await (await pending).text(), payload)
    assert.equal(forwarded, 3)
  } finally {
    child.kill()
    origin.closeAllConnections()
    await new Promise(resolve => origin.close(resolve))
  }
})
