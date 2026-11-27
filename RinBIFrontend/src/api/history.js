import { request } from './http'

/**
 * 当前用户的历史会话列表，按最近更新时间倒序。
 *
 * @param {number} [limit] 返回条数
 * @param {string} [feature] 功能类型：DATA_QA 数据问答 / REPORT 智能报表；留空返回全部
 */
export function fetchConversations(limit = 50, feature = '') {
  const query = new URLSearchParams({ limit: String(limit) })
  if (feature) {
    query.set('feature', feature)
  }
  return request(`/api/history/conversations?${query.toString()}`, {
    timeout: 10000
  })
}

/** 会话详情，包含可恢复的消息与结构化响应。 */
export function fetchConversationDetail(conversationId) {
  return request(`/api/history/conversations/${encodeURIComponent(conversationId)}`, {
    timeout: 15000
  })
}

/** 删除历史会话及其消息。 */
export function deleteHistoryConversation(conversationId) {
  return request(`/api/history/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
    timeout: 10000
  })
}
