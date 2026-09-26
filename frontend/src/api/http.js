import { currentToken, signOut } from '../stores/session.js'

/**
 * 统一的请求封装。
 *
 * <p>用原生 fetch 而不是 axios：需要的只有「加个头、解析 JSON、把错误normalize 成一种形状」，
 * 三件事加起来不到一百行。引一个库来做这个，面试时被问「axios 拦截器怎么实现的」
 * 反而答不上来。</p>
 */

const BASE = '/api'

/** 401 之后要做什么（跳登录页），由 router 注入，避免这里 import router 造成循环依赖。 */
let unauthorizedHandler = () => {}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

/**
 * 后端错误的统一形状。
 *
 * <p><b>永远用 code 做分支，不要用 message。</b>message 是给人看的，随时会改文案；
 * code 是契约。比如结算失败要区分「价格变了」和「幂等键被复用」，
 * 靠匹配中文字符串来判断，后端改一个字前端就瞎了。</p>
 */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/**
 * 把后端的错误体收敛成一种形状。
 *
 * <p><b>这里有一个已知的接缝：</b>commerce-service 返回 <code>{code, message}</code>，
 * 而 order-service 用的是 Spring 的 ProblemDetail（<code>{title, status, detail}</code>），
 * 两个服务的错误体不一样。正确的修法是在 Gateway 上统一，或者让 order-service
 * 也改成同一个 ApiError。现在前端在这里兼容两种，属于把后端的不一致
 * 转嫁给了前端——记着它，不要当成设计。</p>
 */
function normalizeError(status, body) {
  if (body && typeof body === 'object') {
    const code = body.code || (body.status ? `HTTP_${body.status}` : `HTTP_${status}`)
    const message = body.message || body.detail || body.title || '请求失败'
    return new ApiError(status, code, message)
  }
  return new ApiError(status, `HTTP_${status}`, '请求失败')
}

async function parseBody(response) {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

export async function request(path, options = {}) {
  const { method = 'GET', body, headers = {}, anonymous = false } = options

  const finalHeaders = { ...headers }
  if (body !== undefined) {
    finalHeaders['Content-Type'] = 'application/json'
  }

  const token = currentToken()
  if (token && !anonymous) {
    finalHeaders.Authorization = `Bearer ${token}`
  }

  let response
  try {
    response = await fetch(BASE + path, {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (cause) {
    // fetch 只在网络层失败时 reject（断网、DNS、CORS）。
    // 注意它对 4xx/5xx 是 resolve 的——这是 fetch 最容易踩的一个坑，
    // 用 axios 的人换过来经常忘了判 response.ok。
    throw new ApiError(0, 'NETWORK_ERROR', '网络异常，请检查后端服务是否已启动')
  }

  if (response.status === 204) {
    return null
  }

  const payload = await parseBody(response)

  if (response.ok) {
    return payload
  }

  if (response.status === 401 && !anonymous && token === currentToken()) {
    // Only the still-current request token may invalidate this session. A late 401 from a
    // signed-out account (or anonymous login) must not erase a newer user's login.
    // 令牌过期或无效。清掉本地状态再交给上层跳转——
    // 不清的话用户会卡在「一直跳登录页又一直带着坏令牌」的循环里。
    signOut()
    unauthorizedHandler()
  }

  throw normalizeError(response.status, payload)
}

export const http = {
  get: (path, options) => request(path, { ...options, method: 'GET' }),
  post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
  put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
  del: (path, options) => request(path, { ...options, method: 'DELETE' })
}
