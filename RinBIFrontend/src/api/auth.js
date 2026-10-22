import { request } from './http'
import { setToken } from '../utils/session'

/**
 * 登录：成功后立即保存 token，并返回后端下发的用户信息。
 */
export async function login(username, password) {
  const result = await request('/api/auth/login', {
    method: 'POST',
    body: { username, password },
    timeout: 15000
  })
  if (!result || !result.token) {
    throw new Error('登录响应缺少 token')
  }
  setToken(result.token)
  return result
}

/**
 * 注册申请：账号创建后为待审批状态，不会返回 token。
 */
export function register(username, password, displayName) {
  return request('/api/auth/register', {
    method: 'POST',
    body: { username, password, displayName },
    timeout: 15000
  })
}

/** 当前登录用户；token 失效时 request 会清理并广播 401。 */
export function fetchCurrentUser() {
  return request('/api/auth/me', { timeout: 10000 })
}

/** 退出登录：先通知后端销毁 token，本地 token 由调用方清理。 */
export function logout() {
  return request('/api/auth/logout', { method: 'POST', timeout: 10000 })
}
