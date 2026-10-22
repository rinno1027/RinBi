import { request } from './http'

/** 管理员：用户分页，支持关键字与状态筛选。 */
export function fetchAdminUsers(params = {}) {
  return request('/api/admin/users', { params, timeout: 15000 })
}

/** 管理员：审批通过或重新启用账号。 */
export function approveAdminUser(userId) {
  return request(`/api/admin/users/${encodeURIComponent(userId)}/approve`, {
    method: 'POST',
    timeout: 15000
  })
}

/** 管理员：禁用账号；对注册申请等同于拒绝。 */
export function disableAdminUser(userId) {
  return request(`/api/admin/users/${encodeURIComponent(userId)}/disable`, {
    method: 'POST',
    timeout: 15000
  })
}

/** 管理员：全局数据源分页。 */
export function fetchAdminDataSources(params = {}) {
  return request('/api/admin/datasources', { params, timeout: 15000 })
}

/** 管理员：数据源授权弹框数据（全部用户 + 已授权用户 ID）。 */
export function fetchAdminDataSourceUsers(dataSourceId) {
  return request(`/api/admin/datasources/${encodeURIComponent(dataSourceId)}/users`, {
    timeout: 15000
  })
}

/** 管理员：以最终勾选结果覆盖数据源的用户授权。 */
export function assignAdminDataSourceUsers(dataSourceId, userIds) {
  return request(`/api/admin/datasources/${encodeURIComponent(dataSourceId)}/users`, {
    method: 'PUT',
    body: { userIds },
    timeout: 20000
  })
}

export function createAdminDataSource(payload) {
  return request('/api/admin/datasources', {
    method: 'POST',
    body: payload,
    timeout: 20000
  })
}

export function updateAdminDataSource(dataSourceId, payload) {
  return request(`/api/admin/datasources/${encodeURIComponent(dataSourceId)}`, {
    method: 'PUT',
    body: payload,
    timeout: 20000
  })
}

export function deleteAdminDataSource(dataSourceId) {
  return request(`/api/admin/datasources/${encodeURIComponent(dataSourceId)}`, {
    method: 'DELETE',
    timeout: 15000
  })
}

