import { request } from './http'

/** 当前用户的历史会话列表，按最近更新时间倒序。 */
export function fetchConversations(limit = 50) {
  return request(`/api/history/conversations?limit=${encodeURIComponent(limit)}`, {
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
