import { request } from './http'

/** 当前用户可访问数据源范围内的统计规则分页。 */
export function fetchRules(params = {}) {
  return request('/api/rules', { params, timeout: 15000 })
}

/** 对话框规则候选项（按数据源过滤）。 */
export function fetchRuleOptions(dataSourceKey) {
  return request('/api/rules/options', {
    params: { dataSourceKey },
    timeout: 15000
  })
}

/** 规则详情，含输入字段明细。 */
export function fetchRule(ruleId) {
  return request(`/api/rules/${encodeURIComponent(ruleId)}`, { timeout: 15000 })
}

export function createRule(payload) {
  return request('/api/rules', {
    method: 'POST',
    body: payload,
    timeout: 20000
  })
}

export function updateRule(ruleId, payload) {
  return request(`/api/rules/${encodeURIComponent(ruleId)}`, {
    method: 'PUT',
    body: payload,
    timeout: 20000
  })
}

export function deleteRule(ruleId) {
  return request(`/api/rules/${encodeURIComponent(ruleId)}`, {
    method: 'DELETE',
    timeout: 15000
  })
}
