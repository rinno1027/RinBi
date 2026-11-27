import { API_BASE, ApiError, handleUnauthorized, request } from './http'
import { authHeaders } from '../utils/session'

// 报表生成包含模型规划 + 多条 SQL + POI 回填，给足与流式对话同级的时间。
const REPORT_TIMEOUT = 660000
// 模板上传只需要落盘与解析，给一个更短的上限。
const TEMPLATE_TIMEOUT = 120000

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

function parseFileName(disposition, fallback) {
  if (!disposition) {
    return fallback
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1])
    } catch (error) {
      return fallback
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1] : fallback
}

/**
 * 上传 Excel 模板并一次性生成报表（兼容旧版的单次调用）。
 *
 * <p>新的智能报表流程使用 {@link uploadReportTemplate} + {@link continueReport}：
 * 先导入模板创建报表会话，再按需求生成并多轮调整。该函数保留给仍在调用
 * `POST /api/report/generate` 的旧页面。</p>
 *
 * @param {object} options
 * @param {File} options.file 模板文件（.xlsx / .xls）
 * @param {string} [options.requirement] 报表需求，可留空由模型按模板推断
 * @param {string} [options.database] 指定数据源 key
 * @param {Array<number>} [options.ruleIds] 本次报表注入的统计规则 id，仅指定数据源时生效
 * @param {string} [options.conversationId] 会话 id，便于生成记录归入历史
 * @returns {Promise<object>} ChatResponse
 */
export async function generateReport({
  file,
  requirement,
  database,
  ruleIds,
  conversationId
} = {}) {
  if (!file) {
    throw new ApiError('请选择 Excel 模板文件', { code: 'FILE_REQUIRED' })
  }
  const form = new FormData()
  form.append('file', file)
  if (requirement) {
    form.append('requirement', requirement)
  }
  if (database) {
    form.append('database', database)
  }
  if (Array.isArray(ruleIds) && ruleIds.length) {
    // 与 /api/agent/chat 一致：统计规则只随显式选择的数据源生效。
    ruleIds.forEach((ruleId) => form.append('ruleIds', ruleId))
  }
  if (conversationId) {
    form.append('conversationId', conversationId)
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REPORT_TIMEOUT)
  try {
    let response
    try {
      // 不手动设置 Content-Type，交给浏览器补齐 multipart boundary。
      response = await fetch(`${API_BASE}/api/report/generate`, {
        method: 'POST',
        headers: authHeaders(),
        body: form,
        signal: controller.signal
      })
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError('报表生成超时，请缩小统计范围后重试。', { code: 'TIMEOUT' })
      }
      throw new ApiError('无法连接后端服务，请确认服务已启动且代理配置正确。', {
        code: 'NETWORK_ERROR'
      })
    }

    if (!response.ok) {
      const payload = await readErrorPayload(response)
      const message =
        (payload && (payload.message || payload.error)) ||
        `报表生成失败（HTTP ${response.status}）`
      if (response.status === 401) {
        handleUnauthorized(message)
      }
      throw new ApiError(message, {
        code: (payload && payload.code) || 'HTTP_ERROR',
        status: response.status
      })
    }
    return response.json()
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 下载需要 Authorization 头的二进制文件：先取 blob，再触发浏览器保存。
 */
async function downloadBlob({ url, fileName, fallbackName, errorPrefix }) {
  let response
  try {
    response = await fetch(url, { headers: authHeaders() })
  } catch (error) {
    throw new ApiError('无法连接后端服务，请稍后重试。', { code: 'NETWORK_ERROR' })
  }
  if (!response.ok) {
    const payload = await readErrorPayload(response)
    const message =
      (payload && (payload.message || payload.error)) ||
      `${errorPrefix}（HTTP ${response.status}）`
    if (response.status === 401) {
      handleUnauthorized(message)
    }
    throw new ApiError(message, {
      code: (payload && payload.code) || 'HTTP_ERROR',
      status: response.status
    })
  }
  const blob = await response.blob()
  const disposition = response.headers.get('content-disposition') || ''
  const name = parseFileName(disposition, fileName || fallbackName)
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = name
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  return name
}

/**
 * 下载报表文件。下载接口需要 Authorization 头，因此先取 blob 再触发浏览器保存。
 */
export async function downloadReportFile({ fileId, fileName } = {}) {
  if (!fileId) {
    throw new ApiError('报表文件不存在', { code: 'FILE_NOT_FOUND' })
  }
  return downloadBlob({
    url: `${API_BASE}/api/report/files/${encodeURIComponent(fileId)}`,
    fileName,
    fallbackName: `report-${fileId}.xlsx`,
    errorPrefix: '下载失败'
  })
}

/**
 * 下载用户自己导入的 Excel 模板。
 */
export async function downloadReportTemplate({ templateId, templateName } = {}) {
  if (!templateId) {
    throw new ApiError('模板不存在', { code: 'TEMPLATE_NOT_FOUND' })
  }
  return downloadBlob({
    url: `${API_BASE}/api/report/templates/${encodeURIComponent(templateId)}`,
    fileName: templateName,
    fallbackName: `template-${templateId}.xlsx`,
    errorPrefix: '模板下载失败'
  })
}

/** 当前用户导入过的模板列表（含下载地址）。 */
export function fetchReportTemplates() {
  return request('/api/report/templates', { timeout: 15000 })
}

/**
 * 带超时的报表接口请求：统一处理 401、后端错误消息与超时。
 */
async function requestReportApi(url, options, { timeout, timeoutMessage } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    let response
    try {
      response = await fetch(url, { ...options, signal: controller.signal })
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError(timeoutMessage || '请求超时，请稍后重试。', { code: 'TIMEOUT' })
      }
      throw new ApiError('无法连接后端服务，请确认服务已启动且代理配置正确。', {
        code: 'NETWORK_ERROR'
      })
    }
    if (!response.ok) {
      const payload = await readErrorPayload(response)
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
    return response.json()
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 导入 Excel 模板：模板文件保存到服务器，并创建独立的智能报表会话。
 *
 * 一个 conversationId 只支持一个模板；重复导入会返回 400。
 *
 * @param {object} options
 * @param {File} options.file 模板文件（.xlsx / .xls）
 * @param {string} [options.conversationId] 报表会话 id，缺省时由后端生成 rep- 前缀的 id
 * @returns {Promise<object>} ReportTemplateInfo
 */
export function uploadReportTemplate({ file, conversationId } = {}) {
  if (!file) {
    return Promise.reject(new ApiError('请选择 Excel 模板文件', { code: 'FILE_REQUIRED' }))
  }
  const form = new FormData()
  form.append('file', file)
  if (conversationId) {
    form.append('conversationId', conversationId)
  }
  return requestReportApi(
    `${API_BASE}/api/report/templates`,
    { method: 'POST', headers: authHeaders(), body: form },
    { timeout: TEMPLATE_TIMEOUT, timeoutMessage: '模板上传超时，请稍后重试。' }
  )
}

/**
 * 在智能报表会话中生成或调整报表：复用会话绑定的模板，按本轮需求重新规划取数逻辑。
 *
 * 当后端返回 code=INPUT_LIMIT 时，表示该会话累计输入已达模型上限，
 * 需要新建会话并重新上传模板。
 *
 * @param {object} options
 * @param {string} options.conversationId 报表会话 id（必填）
 * @param {number} [options.templateId] 模板 id，缺省时后端按会话查找
 * @param {string} [options.question] 本轮需求或调整说明
 * @param {string} [options.database] 指定数据源 key
 * @param {Array<number>} [options.ruleIds] 本轮注入的统计规则 id
 * @returns {Promise<object>} ChatResponse
 */
export function continueReport({
  conversationId,
  templateId,
  question,
  database,
  ruleIds
} = {}) {
  if (!conversationId) {
    return Promise.reject(
      new ApiError('报表会话不存在，请先上传模板', { code: 'CONVERSATION_REQUIRED' })
    )
  }
  const payload = { conversationId }
  if (templateId) {
    payload.templateId = templateId
  }
  if (question) {
    payload.question = question
  }
  if (database) {
    payload.database = database
  }
  if (Array.isArray(ruleIds) && ruleIds.length) {
    // 与 /api/agent/chat 一致：统计规则只随显式选择的数据源生效。
    payload.ruleIds = ruleIds
  }
  return requestReportApi(
    `${API_BASE}/api/report/chat`,
    {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(payload)
    },
    { timeout: REPORT_TIMEOUT, timeoutMessage: '报表生成超时，请缩小统计范围后重试。' }
  )
}
