<script setup>
import { onMounted, ref } from 'vue'
import * as api from '../api'
import { dateTime, money, orderStatusText, reservationStatusText } from '../utils/format'

const loading = ref(true)
const error = ref('')
const orders = ref([])
const total = ref(0)
// 订单列表的 page 从 0 开始，和商品列表不一样——差异挡在 api/index.js 那一层，
// 但页码本身还是得按各自的约定传，所以这里的初值是 0 而不是 1。
const page = ref(0)
const size = 10

function totalPages() {
  return Math.max(1, Math.ceil(total.value / size))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.listOrders({ page: page.value, size })
    orders.value = result.orders || []
    total.value = result.total || 0
  } catch (caught) {
    error.value = caught.message
    orders.value = []
  } finally {
    loading.value = false
  }
}

function goPage(next) {
  if (next < 0 || next >= totalPages()) {
    return
  }
  page.value = next
  load()
}

function summarize(order) {
  const items = order.items || []
  if (!items.length) {
    return '—'
  }
  // nameSnapshot 可能是 null：通过公开的 POST /api/orders 建的单没有商品上下文。
  // 这时候退化成 SKU 编号，而不是显示一个空的商品名。
  const first = items[0].nameSnapshot || `SKU ${items[0].skuId}`
  return items.length === 1 ? first : `${first} 等 ${items.length} 种商品`
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">我的订单</h1>

  <div v-if="error" class="banner error">{{ error }}</div>

  <div v-if="loading" class="empty">加载中…</div>

  <template v-else-if="orders.length">
    <div class="card" style="padding: 0; overflow: hidden">
      <table>
        <thead>
          <tr>
            <th style="width: 190px">订单号</th>
            <th>商品</th>
            <th style="width: 110px">金额</th>
            <th style="width: 100px">状态</th>
            <th style="width: 150px">库存预占</th>
            <th style="width: 160px">下单时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="order in orders" :key="order.orderId">
            <td>
              <RouterLink
                :to="{ name: 'order-detail', params: { orderId: order.orderId } }"
                style="color: var(--accent)"
              >{{ order.orderId }}</RouterLink>
            </td>
            <td>{{ summarize(order) }}</td>
            <td class="price" style="font-size: 15px">{{ money(order.totalAmount) }}</td>
            <td>{{ orderStatusText(order.status) }}</td>
            <td>
              <span class="tag" :class="{ off: order.reservationStatus === 'FAILED' }">
                {{ reservationStatusText(order.reservationStatus) }}
              </span>
            </td>
            <td class="muted">{{ dateTime(order.createTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages() > 1" class="row" style="justify-content: center; margin-top: 28px">
      <button :disabled="page <= 0" @click="goPage(page - 1)">上一页</button>
      <span class="muted">{{ page + 1 }} / {{ totalPages() }}</span>
      <button :disabled="page >= totalPages() - 1" @click="goPage(page + 1)">下一页</button>
    </div>
  </template>

  <div v-else class="empty">
    还没有订单
    <div style="margin-top: 14px">
      <RouterLink :to="{ name: 'products' }" style="color: var(--accent)">去逛逛 →</RouterLink>
    </div>
  </div>
</template>
