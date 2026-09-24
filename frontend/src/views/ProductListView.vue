<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as api from '../api'
import { money } from '../utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const page = ref(readPage())
const size = 12
const total = ref(0)
const items = ref([])
const categories = ref([])
const keyword = ref(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const categoryId = ref(route.query.categoryId ? Number(route.query.categoryId) : null)

function readPage() {
  // 商品列表的分页从 1 开始（见 api/index.js 里关于两套分页约定的说明）。
  const raw = Number(route.query.page)
  return Number.isInteger(raw) && raw >= 1 ? raw : 1
}

function totalPages() {
  return Math.max(1, Math.ceil(total.value / size))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.listProducts({
      page: page.value,
      size,
      keyword: keyword.value,
      categoryId: categoryId.value
    })
    items.value = result.items || []
    total.value = result.total || 0
  } catch (caught) {
    error.value = caught.message
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// 把筛选条件写回 URL，刷新和「分享这一页」才不会丢。
// 只改 query 不改 path，路由不会重建组件，所以下面用 watch 监听 query 再去 load。
function applyQuery(next) {
  router.push({
    name: 'products',
    query: {
      ...(next.keyword ? { keyword: next.keyword } : {}),
      ...(next.categoryId ? { categoryId: String(next.categoryId) } : {}),
      ...(next.page && next.page > 1 ? { page: String(next.page) } : {})
    }
  })
}

function search() {
  applyQuery({ keyword: keyword.value, categoryId: categoryId.value, page: 1 })
}

function pickCategory(id) {
  categoryId.value = id
  applyQuery({ keyword: keyword.value, categoryId: id, page: 1 })
}

function goPage(next) {
  if (next < 1 || next > totalPages()) {
    return
  }
  applyQuery({ keyword: keyword.value, categoryId: categoryId.value, page: next })
}

watch(() => route.query, () => {
  page.value = readPage()
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  categoryId.value = route.query.categoryId ? Number(route.query.categoryId) : null
  load()
})

onMounted(async () => {
  load()
  try {
    categories.value = await api.listCategories()
  } catch {
    // 分类拉不到就不显示筛选条，商品列表本身还能用。
    // 一个次要模块的失败不应该让整页白屏。
    categories.value = []
  }
})
</script>

<template>
  <h1 class="page-title">商品</h1>

  <div class="row" style="margin-bottom: 16px">
    <form class="row" style="gap: 8px" @submit.prevent="search">
      <input v-model.trim="keyword" type="search" placeholder="搜索商品名" style="width: 240px" />
      <button type="submit">搜索</button>
    </form>
    <span v-if="total" class="muted">共 {{ total }} 件</span>
  </div>

  <div v-if="categories.length" class="row" style="flex-wrap: wrap; gap: 8px; margin-bottom: 20px">
    <button :class="{ primary: categoryId === null }" @click="pickCategory(null)">全部</button>
    <button
      v-for="category in categories"
      :key="category.id"
      :class="{ primary: categoryId === category.id }"
      @click="pickCategory(category.id)"
    >{{ category.name }}</button>
  </div>

  <div v-if="error" class="banner error">{{ error }}</div>

  <div v-if="loading" class="empty">加载中…</div>

  <div v-else-if="!items.length" class="empty">没有找到商品</div>

  <div v-else class="grid">
    <RouterLink
      v-for="item in items"
      :key="item.spuId"
      class="product-card"
      :to="{ name: 'product-detail', params: { spuId: item.spuId } }"
    >
      <div
        class="product-thumb"
        :style="item.coverUrl ? { backgroundImage: `url(${item.coverUrl})` } : null"
      >
        <span v-if="!item.coverUrl">暂无图片</span>
      </div>
      <div class="product-body">
        <div class="product-name">{{ item.name }}</div>
        <div class="muted">{{ item.brand || '　' }}</div>
        <!-- minPrice 可能为 null：SPU 下面一个在售 SKU 都没有。
             这时候显示「暂无在售规格」而不是 ¥0.00——0 元会被当成促销价。 -->
        <div v-if="item.minPrice !== null && item.minPrice !== undefined" class="price">
          {{ money(item.minPrice) }} <span class="muted">起</span>
        </div>
        <div v-else class="muted">暂无在售规格</div>
      </div>
    </RouterLink>
  </div>

  <div v-if="totalPages() > 1" class="row" style="justify-content: center; margin-top: 28px">
    <button :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
    <span class="muted">{{ page }} / {{ totalPages() }}</span>
    <button :disabled="page >= totalPages()" @click="goPage(page + 1)">下一页</button>
  </div>
</template>
