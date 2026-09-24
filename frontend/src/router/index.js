import { createRouter, createWebHistory } from 'vue-router'
import { isSignedIn } from '../stores/session'

const routes = [
  { path: '/', redirect: '/products' },
  {
    path: '/products',
    name: 'products',
    component: () => import('../views/ProductListView.vue')
  },
  {
    path: '/products/:spuId',
    name: 'product-detail',
    component: () => import('../views/ProductDetailView.vue')
  },
  {
    path: '/cart',
    name: 'cart',
    component: () => import('../views/CartView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrderListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders/:orderId',
    name: 'order-detail',
    component: () => import('../views/OrderDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  { path: '/:pathMatch(.*)*', redirect: '/products' }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 前置守卫。
 *
 * <p><b>这只是体验，不是安全。</b>它挡住的是「未登录用户点进购物车看到一片报错」，
 * 挡不住任何攻击——把 meta.requiresAuth 改掉、或者直接在控制台发请求，
 * 都能绕过。真正的鉴权只在 Gateway 验签那一步。</p>
 *
 * <p>前端路由守卫被当成权限控制是很常见的错误认知，面试被问到「前端怎么做权限」
 * 要先把这两件事分开：菜单/路由是展示，接口鉴权是安全。</p>
 */
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !isSignedIn()) {
    // 记下原本要去哪，登录完直接送回去，而不是一律回首页。
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})
