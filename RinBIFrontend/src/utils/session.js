/**
 * 登录态与 token 存储。
 *
 * token 为后端签发的不透明字符串，仅保存在 localStorage 中，
 * 请求时通过 Authorization: Bearer <token> 发送。
 * 收到 401 时统一清理并广播事件，由 App.vue 切回登录页。
 */
export const TOKEN_STORAGE_KEY = 'rinbi.token'
export const UNAUTHORIZED_EVENT = 'rinbi:unauthorized'

function readToken() {
  try {
    return window.localStorage.getItem(TOKEN_STORAGE_KEY) || ''
  } catch (error) {
    return ''
  }
}

let token = readToken()

export function getToken() {
  return token
}

export function setToken(value) {
  token = value || ''
  try {
    if (token) {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, token)
    } else {
      window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  } catch (error) {
    // 隐私模式下 localStorage 可能不可用，内存中的 token 仍然有效。
  }
}

export function clearToken() {
  setToken('')
}

/**
 * 统一的鉴权请求头；未登录时只返回调用方传入的额外头。
 */
export function authHeaders(extra = {}) {
  if (!token) {
    return { ...extra }
  }
  return { ...extra, Authorization: `Bearer ${token}` }
}

/**
 * 广播登录失效事件，避免每个调用点各自处理跳转。
 */
export function notifyUnauthorized() {
  if (typeof window === 'undefined') {
    return
  }
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
}
