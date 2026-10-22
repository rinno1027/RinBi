export const STORAGE_KEYS = {
  conversationId: 'rinbi.conversationId',
  database: 'rinbi.database'
}

const STATUS_META = {
  SUCCESS: { label: '查询成功', type: 'success' },
  NOT_FEASIBLE: { label: '数据不支持', type: 'info' },
  UNSAFE_SQL: { label: '安全拦截', type: 'danger' },
  OUTPUT_TOO_LARGE: { label: '结果过大', type: 'warning' },
  ERROR: { label: '处理失败', type: 'danger' }
}

export function statusMeta(status) {
  return STATUS_META[status] || { label: status || '未知状态', type: 'info' }
}

/**
 * 生成符合后端 [A-Za-z0-9_-]{1,64} 约束的会话 id。
 * 不使用 crypto.randomUUID，以兼容非 HTTPS 的内网访问环境。
 */
export function createConversationId() {
  const random = Math.random().toString(36).slice(2, 10)
  return `web-${Date.now().toString(36)}-${random}`
}

export function formatElapsed(milliseconds) {
  const value = Number(milliseconds)
  if (!Number.isFinite(value) || value < 0) {
    return '—'
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`
  }
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)} s`
}

export function formatDateTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

export function formatTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
}

export function formatCell(value) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return String(value)
}
