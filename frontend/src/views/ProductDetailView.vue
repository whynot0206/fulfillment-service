<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as api from '../api'
import { isSignedIn } from '../stores/session'
import { money, spec, stockHint } from '../utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const product = ref(null)
const selectedSkuId = ref(null)
const quantity = ref(1)
const adding = ref(false)
const notice = ref('')

const selectedSku = computed(
  () => (product.value?.skus || []).find((sku) => sku.skuId === selectedSkuId.value) || null
)

// 「不能买」和「可能买不到」是两回事：
// 下架是确定的，所以禁用按钮；库存未知（null）不禁用——Inventory 查不到不等于没货，
// 真没货由下单时的预占来拒绝。宁可让用户点一次收到「库存不足」，
// 也不要在依赖抖动时把在售商品显示成不可买。
const soldOut = computed(() => selectedSku.value !== null && selectedSku.value.availableStock === 0)
const buyable = computed(() => selectedSku.value !== null && selectedSku.value.onSale && !soldOut.value)

async function load(spuId) {
  loading.value = true
  error.value = ''
  notice.value = ''
  product.value = null
  selectedSkuId.value = null
  quantity.value = 1
  try {
    const detail = await api.productDetail(spuId)
    product.value = detail
    const first = (detail.skus || []).find((sku) => sku.onSale && sku.availableStock !== 0)
      || (detail.skus || [])[0]
    selectedSkuId.value = first ? first.skuId : null
  } catch (caught) {
    error.value = caught.message
  } finally {
    loading.value = false
  }
}

async function addToCart() {
  if (!isSignedIn()) {
    // 加购要带上身份，先去登录，登完回到这一页而不是首页。
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  adding.value = true
  notice.value = ''
  error.value = ''
  try {
    await api.addToCart(selectedSkuId.value, quantity.value)
    notice.value = '已加入购物车'
  } catch (caught) {
    error.value = caught.message
  } finally {
    adding.value = false
  }
}

watch(() => route.params.spuId, (spuId) => spuId && load(spuId), { immediate: true })
</script>

<template>
  <div v-if="loading" class="empty">加载中…</div>

  <template v-else-if="product">
    <p class="muted" style="margin: 20px 0 0">
      <RouterLink :to="{ name: 'products' }">← 返回商品列表</RouterLink>
    </p>

    <h1 class="page-title" style="margin-top: 10px">{{ product.name }}</h1>
    <p v-if="product.brand" class="muted" style="margin-top: -10px">品牌：{{ product.brand }}</p>

    <div style="display: grid; grid-template-columns: minmax(260px, 380px) 1fr; gap: 28px; align-items: start">
      <div>
        <div
          class="product-thumb"
          style="border-radius: 10px; border: 1px solid var(--line)"
          :style="product.images?.length ? { backgroundImage: `url(${product.images[0]})` } : null"
        >
          <span v-if="!product.images?.length">暂无图片</span>
        </div>
      </div>

      <div class="card">
        <div v-if="!product.skus?.length" class="empty" style="padding: 24px 0">
          该商品暂无在售规格
        </div>

        <template v-else>
          <div class="muted">选择规格</div>
          <div class="row" style="flex-wrap: wrap; gap: 8px; margin: 10px 0 18px">
            <button
              v-for="sku in product.skus"
              :key="sku.skuId"
              :class="{ primary: sku.skuId === selectedSkuId }"
              :disabled="!sku.onSale"
              @click="selectedSkuId = sku.skuId; notice = ''"
            >
              {{ spec(sku.specJson) || sku.skuCode }}
            </button>
          </div>

          <template v-if="selectedSku">
            <div class="price" style="font-size: 26px">{{ money(selectedSku.price) }}</div>
            <p class="muted">
              {{ stockHint(selectedSku.availableStock) }}
              <span v-if="!selectedSku.onSale"> · 已下架</span>
            </p>

            <div class="row" style="margin: 18px 0">
              <span class="muted">数量</span>
              <button :disabled="quantity <= 1" @click="quantity = quantity - 1">−</button>
              <input
                v-model.number="quantity"
                type="number"
                min="1"
                max="99"
                style="width: 70px; text-align: center"
              />
              <button :disabled="quantity >= 99" @click="quantity = quantity + 1">＋</button>
            </div>

            <button
              class="primary"
              :disabled="adding || !buyable || !quantity || quantity < 1"
              @click="addToCart"
            >
              {{ adding ? '加入中…' : (soldOut ? '暂时无货' : '加入购物车') }}
            </button>
          </template>
        </template>

        <div v-if="notice" class="banner ok" style="margin-bottom: 0">
          {{ notice }}
          <RouterLink :to="{ name: 'cart' }" style="color: inherit; text-decoration: underline">
            去购物车
          </RouterLink>
        </div>
        <div v-if="error" class="banner error" style="margin-bottom: 0">{{ error }}</div>
      </div>
    </div>

    <div v-if="product.description" class="card" style="margin-top: 24px">
      <div class="muted" style="margin-bottom: 8px">商品描述</div>
      <div style="white-space: pre-wrap">{{ product.description }}</div>
    </div>
  </template>

  <div v-else class="empty">
    <div class="banner error" style="display: inline-block">{{ error || '商品不存在' }}</div>
  </div>
</template>
