/**
 * 金额显示。
 *
 * <p>后端的金额是 DECIMAL(12,2)，经过 JSON 变成 JavaScript 的 double。
 * 显示没问题，<b>但绝不要在前端做金额运算</b>：需要合计就用后端给的
 * subtotal / selectedAmount / totalAmount。前端自己乘加，一是会出
 * 0.1 + 0.2 = 0.30000000000000004 这种结果，二是前端算出来的数和后端算出来的
 * 不一致时，用户看到的和实际扣的就不是一个数。</p>
 *
 * <p>更彻底的做法是后端把金额序列化成字符串（Jackson 配
 * WRITE_BIGDECIMAL_AS_PLAIN + 字符串输出），前端只负责显示不参与解析。
 * MVP 没做，记在这里。</p>
 */
export function money(value) {
  if (value === null || value === undefined) {
    return '—'
  }
  return `¥${Number(value).toFixed(2)}`
}

/** 规格是后端原样透传的 JSON 字符串，解析失败就原样显示，不要让页面崩。 */
export function spec(specJson) {
  if (!specJson) {
    return ''
  }
  try {
    const parsed = JSON.parse(specJson)
    return Object.entries(parsed).map(([key, value]) => `${key}: ${value}`).join(' / ')
  } catch {
    return specJson
  }
}

export function dateTime(value) {
  if (!value) {
    return '—'
  }
  // 后端是 LocalDateTime，序列化成 "2026-09-23T10:11:12"，没有时区。
  // 直接替掉 T 显示，不要 new Date() ——那会按浏览器本地时区再解释一遍，
  // 而这个值本来就已经是服务器本地时间了，会凭空差出几个小时。
  return String(value).replace('T', ' ').slice(0, 19)
}

/**
 * 库存提示。
 *
 * <p>null 是「查不到」（Inventory 不可达），不是 0。显示成「售罄」是错的——
 * 那是把一个只读依赖的故障说成了商品没货。</p>
 */
export function stockHint(availableStock) {
  if (availableStock === null || availableStock === undefined) {
    return '库存未知'
  }
  if (availableStock <= 0) {
    return '暂时无货'
  }
  if (availableStock <= 10) {
    return `仅剩 ${availableStock} 件`
  }
  return '有货'
}

/** 订单状态的中文名。后端返回的是枚举名，前端只做展示映射。 */
const ORDER_STATUS_TEXT = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  CANCELED: '已取消',
  CLOSED: '已关闭'
}

const RESERVATION_STATUS_TEXT = {
  RESERVING: '库存预占中',
  RESERVED: '库存已预占',
  PENDING_COMPENSATION: '结果未知，等待补偿',
  COMPENSATED: '预占已回滚',
  FAILED: '预占失败'
}

export function orderStatusText(status) {
  return ORDER_STATUS_TEXT[status] || status || '—'
}

export function reservationStatusText(status) {
  return RESERVATION_STATUS_TEXT[status] || status || '—'
}
