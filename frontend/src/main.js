import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import { restoreSession } from './stores/session'
import { setUnauthorizedHandler } from './api/http'
import './styles.css'

// 先恢复登录态，再装路由守卫——顺序反了的话，带着有效令牌刷新
// 购物车页面会被守卫当成未登录，白跳一次登录页。
restoreSession()

// 任何接口返回 401 时统一跳登录页。放在这里而不是 http.js 里，
// 是为了让 http.js 不依赖 router（否则 router -> view -> api -> http -> router 成环）。
setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.name === 'login') {
    return
  }
  router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

createApp(App).use(router).mount('#app')
