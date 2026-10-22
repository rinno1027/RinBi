import { authHeaders, clearToken, notifyUnauthorized } from '../utils/session'

export const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
export const DEFAULT_TIMEOUT = 120000

export class ApiError extends Error {
  constructor(message, { code = 'ERROR', status = 0 } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/**
 * 登录失效时清理本地 token 并广播，让应用统一回到登录页。
 */
export function handleUnauthorized(message) {
  clearToken()
  notifyUnauthorized(message)
}

/**
 * 普通 JSON 请求封装：自动附带 token，401 时清理登录态。
 */
export async function request(
  path,
  { method = 'GET', body, timeout = DEFAULT_TIMEOUT, params } = {}
) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  const query = new URLSearchParams()
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        query.set(key, String(value))
      }
    })
  }
  const queryString = query.toString()
  const url = `${API_BASE}${path}${queryString ? `${path.includes('?') ? '&' : '?'}${queryString}` : ''}`

  let response
  try {
    response = await fetch(url, {
      method,
      headers: authHeaders(body === undefined ? undefined : { 'Content-Type': 'application/json' }),
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal
    })
  } catch (error) {
    if (error && error.name === 'AbortError') {
      throw new ApiError('请求超时，请稍后重试或缩小查询范围。', { code: 'TIMEOUT' })
    }
    throw new ApiError('无法连接后端服务，请确认服务已启动且代理配置正确。', {
      code: 'NETWORK_ERROR'
    })
  } finally {
    clearTimeout(timer)
  }

  if (response.status === 204) {
    return null
  }

  const contentType = response.headers.get('content-type') || ''
  let payload = null
  if (contentType.includes('application/json')) {
    try {
      payload = await response.json()
    } catch (error) {
      payload = null
    }
  }

  if (!response.ok) {
    const message =
      (payload && (payload.message || payload.error)) ||
      `请求失败（HTTP ${response.status}）`
    if (response.status === 401) {
      handleUnauthorized(message)
    }
    throw new ApiError(message, {
      code: (payload && payload.code) || 'HTTP_ERROR',
      status: response.status
    })
  }

  return payload
}
