<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import * as api from '../api'
import { dateTime, money, orderStatusText, reservationStatusText, spec } from '../utils/format'

const route = useRoute()

const loading = ref(true)
const error = ref('')
const order = ref(null)
const canceling = ref(false)
const paying = ref(false)

async function pay() {
  if (!order.value || paying.value) return
  const orderId = order.value.orderId
  paying.value = true
  error.value = ''
  try {
    await api.mockPay(orderId)
    await load(orderId)
  } catch (caught) {
    await load(orderId)
    error.value = caught.status === 409 ? '订单状态已变化，请刷新查看' : caught.message
  } finally {
    paying.value = false
  }
}

async function cancel() {
  if (!order.value || canceling.value) return
  const orderId = order.value.orderId
  canceling.value = true
  error.value = ''
  try {
    await api.cancelOrder(orderId)
    await load(orderId)
  } catch (caught) {
    await load(orderId)
    error.value = caught.status === 409 ? '订单状态已变化，请刷新查看' : caught.message
  } finally {
    canceling.value = false
  }
}

async function load(orderId) {
  loading.value = true
  error.value = ''
  order.value = null
  try {
    order.value = await api.orderDetail(orderId)
  } catch (caught) {
    // 别人的订单和不存在的订单都回 404（见 OrderController 的说明：
    // 回 403 等于告诉对方「这个号是存在的」）。所以这里不能把 404 说成「无权查看」。
    error.value = caught.status === 404 ? '订单不存在' : caught.message
  } finally {
    loading.value = false
  }
}

watch(() => route.params.orderId, (orderId) => orderId && load(orderId), { immediate: true })
</script>

<template>
  <p class="muted" style="margin: 20px 0 0">
    <RouterLink :to="{ name: 'orders' }">← 返回订单列表</RouterLink>
  </p>

  <div v-if="loading" class="empty">加载中…</div>

  <template v-else-if="order">
    <h1 class="page-title" style="margin-top: 10px">订单 {{ order.orderId }}</h1>

    <div
      v-if="order.reservationStatus === 'PENDING_COMPENSATION'"
      class="banner warn"
    >
      这笔订单的库存预占结果还没确定，系统正在补偿。请不要重复下单，稍后刷新查看。
    </div>
    <div v-else-if="order.reservationStatus === 'FAILED'" class="banner error">
      库存预占失败{{ order.reservationError ? `：${order.reservationError}` : '' }}
    </div>

    <div class="card">
      <div class="row" style="flex-wrap: wrap; gap: 28px">
        <div>
          <div class="muted">订单状态</div>
          <div style="font-weight: 600">{{ orderStatusText(order.status) }}</div>
        </div>
        <div>
          <div class="muted">库存预占</div>
          <div style="font-weight: 600">{{ reservationStatusText(order.reservationStatus) }}</div>
        </div>
        <div>
          <div class="muted">应付金额</div>
          <div class="price">{{ money(order.totalAmount) }}</div>
        </div>
        <div>
          <div class="muted">下单时间</div>
          <div>{{ dateTime(order.createTime) }}</div>
        </div>
        <div>
          <div class="muted">支付截止</div>
          <div>{{ dateTime(order.expireTime) }}</div>
        </div>
        <div v-if="order.payTime">
          <div class="muted">支付时间</div>
          <div>{{ dateTime(order.payTime) }}</div>
        </div>
        <div v-if="order.outTradeNo">
          <div class="muted">支付流水</div>
          <div>{{ order.outTradeNo }}</div>
        </div>
      </div>
    </div>

    <div v-if="order.status === 'PENDING_PAYMENT'" class="row" style="margin: 16px 0">
      <button v-if="order.reservationStatus === 'RESERVED'" type="button" :disabled="paying || canceling" @click="pay">
        {{ paying ? '支付中…' : '模拟支付' }}
      </button>
      <button type="button" :disabled="canceling" @click="cancel">
        {{ canceling ? '取消中…' : '取消订单' }}
      </button>
    </div>
    <div v-if="error" class="banner error">{{ error }}</div>

    <h2 class="page-title" style="font-size: 17px">商品明细</h2>

    <div class="card" style="padding: 0; overflow: hidden">
      <table>
        <thead>
          <tr>
            <th>商品</th>
            <th style="width: 110px">下单价</th>
            <th style="width: 80px">数量</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in order.items" :key="item.skuId">
            <td>
              <!-- nameSnapshot / specSnapshot 是下单当时的商品文案，可能为 null。
                   null 表示「没记录」，退化成 SKU 编号；不要显示成空商品名。 -->
              <RouterLink
                v-if="item.spuId"
                :to="{ name: 'product-detail', params: { spuId: item.spuId } }"
              >
                <span style="font-weight: 600">{{ item.nameSnapshot || `SKU ${item.skuId}` }}</span>
              </RouterLink>
              <span v-else style="font-weight: 600">{{ item.nameSnapshot || `SKU ${item.skuId}` }}</span>
              <div v-if="item.specSnapshot" class="muted">{{ spec(item.specSnapshot) }}</div>
            </td>
            <td>{{ money(item.price) }}</td>
            <td>×{{ item.count }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="muted" style="margin-top: 18px">
      这里的商品名和价格是下单那一刻的快照。之后商品改名、改价、下架都不会影响这张订单——
      订单记录的是当时达成的那笔交易，不是商品表的当前值。
    </p>

    <p class="muted">“模拟支付”只用于本地演示交易闭环，不会产生真实扣款。</p>
  </template>

  <div v-else class="empty">
    <div class="banner error" style="display: inline-block">{{ error }}</div>
  </div>
</template>
