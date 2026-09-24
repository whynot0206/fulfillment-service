<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as api from '../api'
import { signIn } from '../stores/session'

const route = useRoute()
const router = useRouter()

const mode = ref('login')
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    const result = mode.value === 'login'
      ? await api.login(username.value, password.value)
      : await api.register(username.value, password.value)

    signIn(result)

    // 回到用户原本想去的地方。redirect 来自 URL，属于用户可控输入——
    // 只接受站内相对路径。不校验的话，别人发一条
    // /login?redirect=https://evil.example 的链接，用户登录完就被送出站了，
    // 而地址栏前一刻还是我们自己的域名（open redirect）。
    const target = route.query.redirect
    const safe = typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    await router.replace(safe ? target : { name: 'products' })
  } catch (caught) {
    error.value = caught.message || '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <h1 class="page-title">{{ mode === 'login' ? '登录' : '注册' }}</h1>

  <div class="card" style="max-width: 400px">
    <form @submit.prevent="submit">
      <div style="display: grid; gap: 14px">
        <label>
          <div class="muted">用户名</div>
          <input v-model.trim="username" type="text" autocomplete="username" style="width: 100%" />
        </label>
        <label>
          <div class="muted">密码{{ mode === 'register' ? '（8-72 位）' : '' }}</div>
          <input
            v-model="password"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            style="width: 100%"
          />
        </label>

        <div v-if="error" class="banner error">{{ error }}</div>

        <button class="primary" type="submit" :disabled="busy || !username || !password">
          {{ busy ? '提交中…' : (mode === 'login' ? '登录' : '注册并登录') }}
        </button>
      </div>
    </form>

    <p class="muted" style="margin-bottom: 0">
      {{ mode === 'login' ? '还没有账号？' : '已经有账号了？' }}
      <a
        href="#"
        style="color: var(--accent)"
        @click.prevent="mode = mode === 'login' ? 'register' : 'login'; error = ''"
      >{{ mode === 'login' ? '去注册' : '去登录' }}</a>
    </p>
  </div>

  <p class="muted" style="margin-top: 20px; max-width: 400px">
    注册成功会直接下发令牌并登录。令牌存在浏览器的 localStorage 里，
    有效期 2 小时，过期后任何接口都会回 401 并自动跳回这里。
  </p>
</template>
