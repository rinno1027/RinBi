import { API_BASE, ApiError, handleUnauthorized, request } from './http'
import { authHeaders } from '../utils/session'

// 后端 SseEmitter 超时为 600s，前端多留 60s，确保能先收到服务端的超时/错误事件。
const CHAT_TIMEOUT = 660000

export { ApiError }

/** 后端健康状态。 */
export function fetchHealth() {
  return request('/api/agent/health', { timeout: 10000 })
}

/** 可用数据库列表（不含连接凭据）。 */
export function fetchDatabases() {
  return request('/api/agent/databases', { timeout: 10000 })
}

async function readErrorPayload(response) {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    try {
      return await response.json()
    } catch (error) {
      return null
    }
  }
  try {
    const text = await response.text()
    return text ? { message: text } : null
  } catch (error) {
    return null
  }
}

function parseEventPayload(data) {
  if (!data) {
    return null
  }
  try {
    return JSON.parse(data)
  } catch (error) {
    // 容错：极端情况下服务端直接写了纯文本。
    return data
  }
}

/**
 * 发送自然语言问题，并按 SSE 消费模型的思考过程。
 *
 * 事件契约（均由后端 AgentController 发出）：
 * - meta：查询规划/执行完成后的元信息（含 status、sql、table 等）
 * - reasoning：模型思考过程分片；前端可折叠展示
 * - token：最终答案分片；前端按到达顺序追加，实现边生成边渲染
 * - done：完整 ChatResponse（含 reasoning 与 answer），作为 Promise 的返回值
 * - error：流内异常（ChatStreamError）
 *
 * @param {object} options
 * @param {string} options.question 用户问题
 * @param {string} [options.database] 指定数据源
 * @param {string} [options.conversationId] 会话 id
 * @param {(meta: object) => void} [options.onMeta] 收到 meta 时回调
 * @param {(chunk: string) => void} [options.onReasoning] 收到思考分片时回调
 * @param {(chunk: string) => void} [options.onToken] 收到答案分片时回调
 * @returns {Promise<object>} done 事件中的完整响应
 */
export async function sendQuestion({
  question,
  database,
  conversationId,
  onMeta,
  onReasoning,
  onToken
} = {}) {
  const payload = { question }
  if (database) {
    payload.database = database
  }
  if (conversationId) {
    payload.conversationId = conversationId
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), CHAT_TIMEOUT)
  let reader = null

  try {
    let response
    try {
      response = await fetch(`${API_BASE}/api/agent/chat`, {
        method: 'POST',
        headers: authHeaders({
          'Content-Type': 'application/json',
          // 同时接受 JSON，保证同步校验失败时仍能读取 GlobalExceptionHandler 的 400 响应。
          Accept: 'text/event-stream, application/json'
        }),
        body: JSON.stringify(payload),
        signal: controller.signal
      })
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError('请求超时，请稍后重试或缩小查询范围。', { code: 'TIMEOUT' })
      }
      throw new ApiError('无法连接后端服务，请确认服务已启动且代理配置正确。', {
        code: 'NETWORK_ERROR'
      })
    }

    if (!response.ok) {
      const errorPayload = await readErrorPayload(response)
      const message =
        (errorPayload && (errorPayload.message || errorPayload.error)) ||
        `请求失败（HTTP ${response.status}）`
      if (response.status === 401) {
        handleUnauthorized(message)
      }
      throw new ApiError(message, {
        code: (errorPayload && errorPayload.code) || 'HTTP_ERROR',
        status: response.status
      })
    }

    if (!response.body) {
      throw new ApiError('当前浏览器不支持流式响应，请更换现代浏览器后重试。', {
        code: 'STREAM_UNSUPPORTED'
      })
    }

    reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let eventName = ''
    let dataLines = []
    let finalResponse = null
    let streamError = null

    const dispatch = (name, data) => {
      const parsed = parseEventPayload(data)
      if (name === 'meta') {
        if (parsed && typeof parsed === 'object' && typeof onMeta === 'function') {
          onMeta(parsed)
        }
        return
      }
      if (name === 'reasoning') {
        if (typeof parsed === 'string' && typeof onReasoning === 'function') {
          onReasoning(parsed)
        }
        return
      }
      if (name === 'token') {
        if (typeof parsed === 'string' && typeof onToken === 'function') {
          onToken(parsed)
        }
        return
      }
      if (name === 'done') {
        if (parsed && typeof parsed === 'object') {
          finalResponse = parsed
        }
        return
      }
      if (name === 'error') {
        const info = parsed && typeof parsed === 'object' ? parsed : { message: parsed }
        streamError = new ApiError(info.message || '处理请求时发生错误，请稍后重试。', {
          code: info.code || 'STREAM_ERROR',
          status: Number(info.status) || 0
        })
      }
    }

    const handleLine = (line) => {
      if (line === '') {
        if (dataLines.length) {
          dispatch(eventName || 'message', dataLines.join('\n'))
        }
        eventName = ''
        dataLines = []
        return
      }
      if (line.startsWith(':')) {
        return
      }
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) {
        value = value.slice(1)
      }
      if (field === 'event') {
        eventName = value
      } else if (field === 'data') {
        dataLines.push(value)
      }
    }

    // 流式读取，按 SSE 的空行边界分发事件，未读完的行留在 buffer 中。
    const drainBuffer = (flush = false) => {
      let lineEnd = buffer.indexOf('\n')
      while (lineEnd >= 0) {
        let line = buffer.slice(0, lineEnd)
        buffer = buffer.slice(lineEnd + 1)
        if (line.endsWith('\r')) {
          line = line.slice(0, -1)
        }
        handleLine(line)
        lineEnd = buffer.indexOf('\n')
      }
      if (flush && buffer) {
        handleLine(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
        buffer = ''
      }
    }

    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      drainBuffer()
      if (streamError) {
        try {
          await reader.cancel()
        } catch (error) {
          // 流已关闭时 cancel 会抛错，忽略即可。
        }
        throw streamError
      }
    }

    buffer += decoder.decode()
    drainBuffer(true)
    if (streamError) {
      throw streamError
    }
    if (!finalResponse) {
      throw new ApiError('连接已中断，未收到完整结果，请重试。', {
        code: 'STREAM_INTERRUPTED'
      })
    }
    return finalResponse
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    if (error && error.name === 'AbortError') {
      throw new ApiError('请求超时，请稍后重试或缩小查询范围。', { code: 'TIMEOUT' })
    }
    throw new ApiError('读取流式响应失败，请稍后重试。', { code: 'STREAM_ERROR' })
  } finally {
    clearTimeout(timer)
    if (reader) {
      try {
        reader.releaseLock()
      } catch (error) {
        // reader 已被 cancel 时 releaseLock 可能失败，忽略即可。
      }
    }
  }
}

/** 清空服务端会话记忆。 */
export function deleteConversation(conversationId) {
  return request(`/api/agent/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
    timeout: 10000
  })
}
