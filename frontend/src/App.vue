<script setup>
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { session, isSignedIn, signOut } from './stores/session'

const router = useRouter()

function logout() {
  signOut()
  router.push({ name: 'products' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <RouterLink class="brand" :to="{ name: 'products' }">履约商城</RouterLink>
      <nav class="nav">
        <RouterLink :to="{ name: 'products' }" active-class="active">商品</RouterLink>
        <RouterLink :to="{ name: 'cart' }" active-class="active">购物车</RouterLink>
        <RouterLink :to="{ name: 'orders' }" active-class="active">我的订单</RouterLink>
        <template v-if="isSignedIn()">
          <span class="muted">{{ session.username }}</span>
          <button @click="logout">退出</button>
        </template>
        <RouterLink v-else :to="{ name: 'login' }" active-class="active">登录</RouterLink>
      </nav>
    </div>
  </header>

  <main class="shell">
    <RouterView />
  </main>
</template>
