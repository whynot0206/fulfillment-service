<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as api from '../api'
import { currentCheckoutKey, rotateCheckoutKey, checkoutExpectedAmount, forgetCheckoutAmount } from '../api/checkout'
import { session } from '../stores/session'
import { money, spec, stockHint } from '../utils/format'

const router = useRouter()

const loading = ref(true)
const cart = ref(null)
const error = ref('')
const warning = ref('')
const busySkuId = ref(null)
const submitting = ref(false)
/** 幂等键已被绑到另一份载荷上，必须先确认再换新键，见下面 handleCheckoutError。 */
const keyStuck = ref(false)
const stuckKey = ref(null)
let mounted = true
let sessionRevision = 0
// Synchronous invalidation also detects logout/login to the SAME account/token.
watch(() => [session.userId, session.token], () => {
  sessionRevision++
  submitting.value = false
  keyStuck.value = false
  stuckKey.value = null
}, { flush: 'sync' })
onBeforeUnmount(() => { mounted = false })

function checkoutContext() {
  const userId = session.userId
  const token = session.token
  const revision = sessionRevision
  return { userId, key: null, active: () => mounted && sessionRevision === revision
    && session.userId === userId && session.token === token }
}
/**
 * 这次失败是否可能已经产生了订单。
 *
 * 只有这种情况才引导用户去订单列表。价格变了、购物车空了这类失败是确定没下单的，
 * 也提示「去确认订单」只会让用户以为自己可能重复下单了——没有信息的提示比没有提示更糟。
 */
const mayHaveOrdered = ref(false)

async function load() {
  loading.value = true
  try {
    cart.value = await api.viewCart()
  } catch (caught) {
    error.value = caught.message
  } finally {
    loading.value = false
  }
}

// 每个改购物车的接口都返回最新的 CartView，直接用返回值覆盖本地状态，
// 不要在前端自己改数量再自己乘一遍小计——selectedAmount 只认后端算出来的那个。
async function mutate(skuId, action) {
  busySkuId.value = skuId
  error.value = ''
  try {
    cart.value = await action()
  } catch (caught) {
    error.value = caught.message
    // 失败了本地状态可能已经和后端不一致（比如商品被下架），重新拉一次对齐。
    await load()
  } finally {
    busySkuId.value = null
  }
}

function changeQuantity(item, delta) {
  const next = item.quantity + delta
  if (next < 1 || next > 99) {
    return
  }
  return mutate(item.skuId, () => api.updateCartQuantity(item.skuId, next))
}

function toggleSelected(item) {
  return mutate(item.skuId, () => api.updateCartSelected(item.skuId, !item.selected))
}

function remove(item) {
  return mutate(item.skuId, () => api.removeCartItem(item.skuId))
}

async function checkout() {
  if (submitting.value || !cart.value) return
  const context = checkoutContext()
  submitting.value = true
  error.value = ''
  warning.value = ''
  mayHaveOrdered.value = false
  try {
    context.key = await currentCheckoutKey(context.userId)
    if (!context.active()) return
    const amount = await checkoutExpectedAmount(context.key, cart.value.selectedAmount, context.userId)
    if (!context.active()) return
    const result = await api.submitCheckout(context.key, amount)
    // An old response may neither navigate the new account nor consume its intent.
    if (!context.active()) return

    if (result.state === 'RESERVED') {
      if (!await rotateCheckoutKey(context.userId, context.key, context.active) || !context.active()) return
      await router.push({ name: 'order-detail', params: { orderId: result.orderId },
        query: result.cartCleanupRequired ? { retainedCart: '1' } : {} })
      return
    }

    // Only explicit terminal outcomes close an intent. Unknown/future 200 states stay unresolved.
    if (['FAILED', 'COMPENSATED', 'CANCELED', 'CLOSED'].includes(result.state)) {
      if (!await rotateCheckoutKey(context.userId, context.key, context.active) || !context.active()) return
      warning.value = result.message || '原订单已结束，请核对后再开始新结算'
      await load()
    } else {
      mayHaveOrdered.value = true
      warning.value = '原结算尚未确认，请保留原凭证并先查看订单'
    }
  } catch (caught) {
    if (context.active()) await handleCheckoutError(caught, context)
  } finally {
    if (context.active()) submitting.value = false
  }
}

/**
 * 结算失败的分支。
 *
 * <p><b>一律按 code 判，不看 message。</b>message 是文案，随时会改。</p>
 *
 * <p>真正要想清楚的是「这一类失败之后，幂等键该不该换」：</p>
 * <ul>
 *   <li>结果未知（网络断了、订单服务超时、补偿中）→ <b>不换</b>。订单可能已经建好了，
 *       只是我们没收到。带着同一个键重试，后端会认出重放把原结果还回来；
 *       换了键就是又下一单。</li>
 *   <li>校验类失败（价格变了、下架了、库存不足、购物车空）→ 后端还没落幂等记录，
 *       换不换都行，保留即可。</li>
 *   <li>同键异载荷（IDEMPOTENCY_KEY_REUSED）→ 这个键已经绑死在另一份购物车上了，
 *       不换永远提交不了。但也不能偷偷换：上一次那笔可能真的成功了，
 *       用户得先去订单列表确认，再由他点一下继续。</li>
 * </ul>
 */
async function handleCheckoutError(caught, context) {
  switch (caught.code) {
    case 'CHECKOUT_RESULT_UNKNOWN':
    case 'CHECKOUT_IN_PROGRESS':
    case 'CHECKOUT_RECOVERY_REQUIRED':
    case 'NETWORK_ERROR':
      mayHaveOrdered.value = true
      warning.value = `${caught.message}（请先确认上一笔的去向，不要重复提交）`
      break
    case 'IDEMPOTENCY_KEY_REUSED':
      keyStuck.value = true
      stuckKey.value = context.key
      mayHaveOrdered.value = true
      warning.value = '这次提交用的幂等键已经对应另一笔结算。请先确认上一笔是否已经生成订单。'
      break
    case 'PRICE_CHANGED':
      await forgetCheckoutAmount(context.userId, context.key, context.active)
      if (!context.active()) return
      warning.value = '商品价格已变化，已为你刷新，请确认新的合计金额后重新提交。'
      load()
      break
    case 'CART_EMPTY':
      await forgetCheckoutAmount(context.userId, context.key, context.active)
      if (!context.active()) return
      warning.value = '没有勾选任何商品。'
      load()
      break
    case 'SKU_NOT_ON_SALE':
    case 'SKU_NOT_FOUND':
    case 'INSUFFICIENT_STOCK':
    case 'INVALID_QUANTITY':
      await forgetCheckoutAmount(context.userId, context.key, context.active)
      if (!context.active()) return
      warning.value = caught.message
      load()
      break
    default:
      error.value = caught.message
  }
}

/** 用户已经自己确认过上一笔的去向，换一把新键重新开始。 */
async function resetKeyAndRetry() {
  if (submitting.value || !stuckKey.value) return
  const context = checkoutContext()
  context.key = stuckKey.value
  submitting.value = true
  let reset = false
  try {
    reset = await rotateCheckoutKey(context.userId, context.key, context.active)
    if (!context.active() || !reset) return
    keyStuck.value = false
    stuckKey.value = null
    mayHaveOrdered.value = false
    warning.value = ''
  } catch (caught) {
    if (context.active()) warning.value = caught.message
  } finally {
    if (context.active()) submitting.value = false
  }
  if (context.active() && reset) return checkout()
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">购物车</h1>

  <div v-if="loading" class="empty">加载中…</div>

  <template v-else-if="cart && cart.items.length">
    <div v-if="error" class="banner error">{{ error }}</div>
    <div v-if="warning" class="banner warn">
      {{ warning }}
      <RouterLink
        v-if="mayHaveOrdered"
        :to="{ name: 'orders' }"
        style="text-decoration: underline"
      >去「我的订单」查看</RouterLink>
      <button v-if="keyStuck" :disabled="submitting" style="margin-left: 10px" @click="resetKeyAndRetry">
        已确认，重新下单
      </button>
    </div>

    <div class="card" style="padding: 0; overflow: hidden">
      <table>
        <thead>
          <tr>
            <th style="width: 42px"></th>
            <th>商品</th>
            <th style="width: 110px">单价</th>
            <th style="width: 150px">数量</th>
            <th style="width: 110px">小计</th>
            <th style="width: 70px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in cart.items" :key="item.skuId">
            <td>
              <input
                type="checkbox"
                :checked="item.selected"
                :disabled="busySkuId === item.skuId || !item.onSale"
                @change="toggleSelected(item)"
              />
            </td>
            <td>
              <RouterLink :to="{ name: 'product-detail', params: { spuId: item.spuId } }">
                <div style="font-weight: 600">{{ item.name }}</div>
              </RouterLink>
              <div class="muted">{{ spec(item.specJson) }}</div>
              <div class="muted">
                <span v-if="!item.onSale" class="tag off">已下架</span>
                <span v-else>{{ stockHint(item.availableStock) }}</span>
              </div>
            </td>
            <td>{{ money(item.price) }}</td>
            <td>
              <div class="row" style="gap: 6px">
                <button
                  :disabled="busySkuId === item.skuId || item.quantity <= 1"
                  @click="changeQuantity(item, -1)"
                >−</button>
                <span style="min-width: 24px; text-align: center">{{ item.quantity }}</span>
                <button
                  :disabled="busySkuId === item.skuId || item.quantity >= 99"
                  @click="changeQuantity(item, 1)"
                >＋</button>
              </div>
            </td>
            <td class="price" style="font-size: 15px">{{ money(item.subtotal) }}</td>
            <td>
              <button :disabled="busySkuId === item.skuId" @click="remove(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="summary-bar">
      <span class="muted">已选 {{ cart.selectedCount }} 项</span>
      <span>合计</span>
      <span class="total">{{ money(cart.selectedAmount) }}</span>
      <button
        class="primary"
        :disabled="submitting || !cart.selectedCount || keyStuck"
        @click="checkout"
      >
        {{ submitting ? '提交中…' : '去结算' }}
      </button>
    </div>

    <p class="muted" style="margin-top: 18px">
      首次结算会重新校验价格。结果未知后重试会继续核对原订单及原确认金额，
      不会将后来修改的购物车当成另一笔订单；请先确认上一笔结果，再开始新结算。
    </p>
  </template>

  <div v-else class="empty">
    购物车是空的
    <div style="margin-top: 14px">
      <RouterLink :to="{ name: 'products' }" style="color: var(--accent)">去挑点东西 →</RouterLink>
    </div>
  </div>
</template>
