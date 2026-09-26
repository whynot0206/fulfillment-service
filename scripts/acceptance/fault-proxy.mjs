// Loopback-only acceptance fault proxy. Never deploy this as an application service.
// No dependencies, payload/header logging, business-state mutation, or public listeners.
import http from 'node:http'
import { timingSafeEqual } from 'node:crypto'

const port = Number(process.env.FAULT_PROXY_PORT)
const upstream = new URL(process.env.FAULT_PROXY_UPSTREAM)
const controlToken = process.env.FAULT_PROXY_TOKEN || ''
if (!Number.isInteger(port) || port < 1024 || port > 65535 ||
    upstream.protocol !== 'http:' || upstream.hostname !== '127.0.0.1' ||
    upstream.username || upstream.password || upstream.pathname !== '/' ||
    upstream.search || upstream.hash || controlToken.length < 24) {
  throw new Error('Explicit loopback upstream, port and generated control token required')
}
let fault = { mode: 'pass', path: '', remaining: 0 }
let hits = 0
let forwarded = 0
let lastFault = null
const held = new Set()
const equalToken = value => {
  const a = Buffer.from(value || ''), b = Buffer.from(controlToken)
  return a.length === b.length && timingSafeEqual(a, b)
}
const json = (res, code, value) => {
  res.writeHead(code, { 'content-type': 'application/json' })
  res.end(JSON.stringify(value))
}
const handle = async (req, res) => {
  if (req.url === '/__fault__/state') {
    if (!equalToken(req.headers['x-fault-token'])) return json(res, 403, { error: 'denied' })
    if (req.method === 'POST') {
      let size = 0; const chunks = []
      for await (const chunk of req) {
        size += chunk.length
        if (size > 4096) return json(res, 413, { error: 'too large' })
        chunks.push(chunk)
      }
      try {
        const next = JSON.parse(Buffer.concat(chunks).toString())
        if (!['pass', 'drop-response', 'hold-before', 'hold-after', 'reject'].includes(next.mode) ||
            (next.mode !== 'pass' && !['/internal/orders', '/internal/orders/create-and-resolve', '/internal/inventory/reserve',
              '/internal/inventory/confirm', '/internal/inventory/release'].includes(next.path)) ||
            !Number.isInteger(next.remaining) || next.remaining < 0 || next.remaining > 100) {
          return json(res, 400, { error: 'invalid fault specification' })
        }
        fault = { mode: next.mode, path: next.path || '', remaining: next.remaining }
        if (next.mode === 'pass') {
          for (const release of [...held]) release()
        }
      } catch { return json(res, 400, { error: 'invalid JSON' }) }
    } else if (req.method !== 'GET') return json(res, 405, { error: 'method' })
    return json(res, 200, { ...fault, hits, forwarded, held: held.size, lastFault })
  }
  if (req.url === '/__fault__/health') return json(res, 200, { status: 'UP' })
  const matches = req.url === fault.path || (fault.path === '/internal/orders/create-and-resolve'
    && ['/internal/orders', '/internal/orders/resolve-create'].includes(req.url))
  const selected = req.method === 'POST' && matches && fault.remaining > 0
  const mode = selected ? fault.mode : 'pass'
  if (selected) { fault.remaining--; hits++ }
  const trace = selected ? { sequence: hits, mode, path: req.url, completed: false,
    upstreamStatus: null, businessStatus: null } : null
  if (trace) lastFault = trace
  const body = []; let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > 1024 * 1024) return json(res, 413, { error: 'too large' })
    body.push(chunk)
  }
  if (mode === 'reject') return json(res, 503, { error: 'acceptance dependency unavailable' })
  const pause = () => new Promise(resolve => {
    // Explicit pass releases held traffic, including late requests after a client crash.
    const release = () => { clearTimeout(timer); held.delete(release); resolve() }
    const timer = setTimeout(release, 120000)
    held.add(release)
  })
  if (mode === 'hold-before') await pause()
  const headers = { ...req.headers, host: upstream.host }
  delete headers['x-fault-token']
  const remote = http.request({ hostname: upstream.hostname, port: upstream.port,
    path: req.url, method: req.method, headers }, async response => {
    const chunks = []
    response.on('data', chunk => chunks.push(chunk))
    response.on('end', async () => {
      forwarded++
      if (trace) {
        trace.completed = true
        trace.upstreamStatus = response.statusCode
        // Only a status label is retained; raw payloads and IDs are never logged/rewritten.
        try {
          const label = JSON.parse(Buffer.concat(chunks).toString()).status
          if (typeof label === 'string' && /^[A-Z_]{1,40}$/.test(label)) trace.businessStatus = label
        } catch { }
      }
      if (mode === 'hold-after') await pause()
      if (mode === 'drop-response') { res.destroy(); return }
      if (res.destroyed) return
      res.writeHead(response.statusCode, response.headers)
      res.end(Buffer.concat(chunks))
    })
    response.on('error', () => { if (!res.destroyed) res.destroy() })
  })
  remote.setTimeout(10000, () => remote.destroy())
  remote.on('error', () => { if (!res.destroyed) json(res, 502, { error: 'upstream unavailable' }) })
  remote.end(Buffer.concat(body))
}
const server = http.createServer((req, res) => {
  handle(req, res).catch(() => { if (!res.destroyed) res.destroy() })
})
server.listen(port, '127.0.0.1', () => console.log(`Acceptance fault proxy listening on 127.0.0.1:${port}`))
