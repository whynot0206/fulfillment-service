import { reactive, readonly } from 'vue'

const STORAGE_KEY = 'fulfillment.session'

/**
 * 登录态。
 *
 * <h2>令牌放在 localStorage，这是一个有代价的选择</h2>
 * <p>好处是刷新页面不掉登录，写起来也简单。代价是任何一段能在本页执行的
 * JavaScript 都能读到它——也就是说一个 XSS 就等于一次账号泄露。</p>
 *
 * <p>真正的答案是让后端用 <code>HttpOnly; Secure; SameSite=Lax</code> 的 Cookie 下发令牌，
 * JS 根本读不到。但那样又要处理 CSRF（Cookie 会被浏览器自动带上，
 * 所以必须再加 SameSite 或 CSRF token），而且后端要改成写 Cookie 而不是回 JSON。
 * MVP 里没做这一步，但<b>不能把它说成没有问题</b>——面试问到「令牌存哪」，
 * 标准答案是 HttpOnly Cookie + CSRF 防护，localStorage 是在知道风险后的取舍。</p>
 *
 * <p>另一件这里<b>没有</b>做的事：令牌续期。后端签发的 ttl 是 7200 秒，到点就失效，
 * 用户正在下单也会被踢回登录页。refresh token 的完整方案（短 access + 长 refresh +
 * 轮换 + 失效撤销）超出 MVP 范围，这里只做到「过期后干净地回登录页」。</p>
 */
const state = reactive({
  token: null,
  userId: null,
  username: null
})

function persist() {
  if (state.token) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: state.token,
      userId: state.userId,
      username: state.username
    }))
  } else {
    localStorage.removeItem(STORAGE_KEY)
  }
}

export function restoreSession() {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) {
    return
  }
  try {
    const saved = JSON.parse(raw)
    state.token = saved.token || null
    state.userId = saved.userId || null
    state.username = saved.username || null
  } catch {
    // 存的东西坏了（改过版本、手工编辑过）。直接丢掉，不要让首页因为它白屏。
    localStorage.removeItem(STORAGE_KEY)
  }
}

export function signIn({ token, userId, username }) {
  state.token = token
  state.userId = userId
  state.username = username
  persist()
}

export function signOut() {
  state.token = null
  state.userId = null
  state.username = null
  persist()
}

export function currentToken() {
  return state.token
}

export function isSignedIn() {
  return Boolean(state.token)
}

/** 只读视图，组件里不能直接改登录态——改只能走 signIn / signOut。 */
export const session = readonly(state)
