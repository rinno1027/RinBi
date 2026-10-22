<template>
  <div v-if="authChecking" class="auth-loading">
    <span class="typing-dots"><i></i><i></i><i></i></span>
    <span>正在确认登录状态…</span>
  </div>

  <LoginView v-else-if="!currentUser" @authenticated="handleAuthenticated" />

  <div v-else class="app-shell">
    <header class="app-header">
      <div class="brand">
        <div class="brand-mark">R</div>
        <div class="brand-text">
          <h1>RinBI 数据问答</h1>
          <p>自然语言 → 安全只读 SQL → 可视化结果</p>
        </div>
      </div>

      <div class="header-actions">
        <span class="service-state" :class="serviceClass">
          <span class="service-dot"></span>
          {{ serviceText }}
        </span>
        <span class="user-chip" :title="currentUser.username">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-name">{{ currentUser.displayName || currentUser.username }}</span>
        </span>
        <button
          v-if="isAdmin"
          type="button"
          class="admin-toggle-button"
          :class="{ active: adminMode }"
          @click="adminMode = !adminMode"
        >
          {{ adminMode ? '返回问答' : '用户管理' }}
        </button>
        <button
          v-if="!adminMode"
          type="button"
          class="clear-button"
          :disabled="sending || clearing || (!messages.length && !conversationId)"
          @click="handleClear"
        >
          {{ clearing ? '清空中…' : '清空对话' }}
        </button>
        <button type="button" class="logout-button" :disabled="loggingOut" @click="handleLogout">
          {{ loggingOut ? '退出中…' : '退出' }}
        </button>
      </div>
    </header>

    <AdminPanel
      v-if="adminMode && isAdmin"
      :current-user="currentUser"
      @close="adminMode = false"
    />

    <div v-else class="app-body">
      <aside class="side-panel">
        <section class="panel-card">
          <div class="panel-title-row">
            <h2 class="panel-title">数据源</h2>
            <span v-if="loadingMeta" class="panel-loading">加载中…</span>
          </div>

          <el-select
            v-model="selectedDatabase"
            size="large"
            class="db-select"
            placeholder="自动选择数据源"
            :disabled="!databases.length"
          >
            <el-option label="自动选择（由模型判断）" value="" />
            <el-option
              v-for="database in databases"
              :key="database.key"
              :label="database.name"
              :value="database.key"
            >
              <span class="db-option-name">{{ database.name }}</span>
              <span class="db-option-dialect">{{ database.dialect }}</span>
            </el-option>
          </el-select>

          <div v-if="currentDatabase" class="db-detail">
            <p>{{ currentDatabase.description || '暂无数据源说明' }}</p>
            <div class="db-tags">
              <el-tag size="small" effect="plain">{{ currentDatabase.dialect }}</el-tag>
              <el-tag size="small" effect="plain" type="info">{{ currentDatabase.key }}</el-tag>
            </div>
          </div>
          <div v-else class="db-detail">
            <p>不指定数据源时，模型会根据问题从已配置的数据源中自动选择。</p>
          </div>
          <p v-if="!loadingMeta && !databases.length" class="panel-hint">
            暂无可用数据源，请检查后端配置。
          </p>
        </section>

        <section class="panel-card history-card">
          <div class="panel-title-row">
            <h2 class="panel-title">历史对话</h2>
            <button
              type="button"
              class="panel-refresh"
              :disabled="loadingHistory"
              @click="loadHistory"
            >
              {{ loadingHistory ? '加载中…' : '刷新' }}
            </button>
          </div>

          <ul v-if="historyList.length" class="history-list">
            <li
              v-for="item in historyList"
              :key="item.conversationId"
              class="history-item"
              :class="{ active: item.conversationId === conversationId }"
            >
              <button
                type="button"
                class="history-main"
                :disabled="sending || Boolean(restoringId)"
                @click="restoreConversation(item.conversationId)"
              >
                <span class="history-title">{{ item.title || '未命名对话' }}</span>
                <span class="history-meta">
                  <span>{{ formatDateTime(item.updatedAt) }}</span>
                  <span v-if="item.messageCount">{{ item.messageCount }} 条消息</span>
                </span>
              </button>
              <button
                type="button"
                class="history-delete"
                title="删除该会话"
                :disabled="Boolean(deletingHistoryId)"
                @click.stop="removeHistory(item.conversationId)"
              >
                ×
              </button>
            </li>
          </ul>
          <p v-else class="history-empty">
            {{ loadingHistory ? '正在加载历史对话…' : '暂无历史对话，提问后会自动保存。' }}
          </p>
        </section>

        <section class="panel-card">
          <h2 class="panel-title">提问示例</h2>
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            class="example-item"
            @click="useExample(example)"
          >
            <span class="example-arrow">↗</span>
            <span>{{ example }}</span>
          </button>
        </section>

        <section class="panel-card safety-card">
          <h2 class="panel-title">安全边界</h2>
          <ul class="safety-list">
            <li>仅允许 SELECT / WITH … SELECT 只读语句</li>
            <li>结果行数、字符数与执行时间均有上限</li>
            <li>数据库凭据只保留在后端，不下发前端</li>
          </ul>
        </section>

        <div class="session-box">
          <span class="session-label">当前会话</span>
          <code class="session-id">{{ conversationId || '首次提问时自动创建' }}</code>
        </div>
      </aside>

      <main class="chat-panel">
        <div ref="scrollRef" class="chat-scroll">
          <div v-if="!messages.length" class="hero">
            <span class="hero-badge">自然语言 · 安全 SQL · 多数据源</span>
            <h2>把业务问题，直接问成数据</h2>
            <p class="hero-description">
              选择数据源或用自然语言提问。Agent 会自动理解表结构、生成只读查询，
              并把结果整理成表格与分析说明。
            </p>
            <div class="hero-chips">
              <button
                v-for="example in examples"
                :key="example"
                type="button"
                class="hero-chip"
                @click="useExample(example)"
              >
                {{ example }}
              </button>
            </div>
            <div class="hero-features">
              <span>只读查询</span>
              <span>自动选库</span>
              <span>SQL 可追溯</span>
            </div>
          </div>

          <div v-else class="message-list">
            <ChatMessage
              v-for="message in messages"
              :key="message.id"
              :message="message"
            />
          </div>
        </div>

        <div class="composer-area">
          <el-alert
            v-if="pageError"
            class="page-alert"
            type="warning"
            :title="pageError"
            show-icon
            closable
            @close="pageError = ''"
          />

          <div class="composer-card">
            <el-input
              ref="composerRef"
              v-model="question"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              resize="none"
              maxlength="2000"
              placeholder="例如：2026 年各产品类别的销售额排名"
              :disabled="sending"
              @keydown="handleKeydown"
            />
            <div class="composer-actions">
              <span v-if="question.length" class="char-count">{{ question.length }} / 2000</span>
              <el-button
                type="primary"
                class="send-button"
                :loading="sending"
                :disabled="!canSend"
                @click="handleSend"
              >
                发送
              </el-button>
            </div>
          </div>

          <div class="composer-tip">
            <span><kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行</span>
            <span>仅执行只读查询 · 重要数据请先脱敏</span>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPanel from './components/AdminPanel.vue'
import ChatMessage from './components/ChatMessage.vue'
import LoginView from './components/LoginView.vue'
import { deleteConversation, fetchDatabases, fetchHealth, sendQuestion } from './api/agent'
import { fetchCurrentUser, logout } from './api/auth'
import {
  deleteHistoryConversation,
  fetchConversationDetail,
  fetchConversations
} from './api/history'
import { createConversationId, formatDateTime, STORAGE_KEYS } from './utils/format'
import { clearToken, getToken, UNAUTHORIZED_EVENT } from './utils/session'

const examples = [
  '按地区统计客户数量，并计算每个地区的客户占比',
  '2026 年各产品类别的销售额排名',
  '列出金额最高的 5 个订单及其客户名称',
  '找出从未下过订单的客户'
]

const currentUser = ref(null)
const authChecking = ref(true)
const loggingOut = ref(false)
const adminMode = ref(false)
const historyList = ref([])
const loadingHistory = ref(false)
const restoringId = ref('')
const deletingHistoryId = ref('')
const messages = ref([])
const question = ref('')
const databases = ref([])
const selectedDatabase = ref(readStorage(STORAGE_KEYS.database))
const conversationId = ref(readStorage(STORAGE_KEYS.conversationId))
const serviceOnline = ref(null)
const pageError = ref('')
const sending = ref(false)
const clearing = ref(false)
const loadingMeta = ref(false)
const scrollRef = ref(null)
const composerRef = ref(null)

let messageSequence = 0
let scrollFrame = null

const userInitial = computed(() => {
  const name = currentUser.value?.displayName || currentUser.value?.username || '?'
  return name.trim().charAt(0).toUpperCase()
})
const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')
const currentDatabase = computed(() =>
  databases.value.find((database) => database.key === selectedDatabase.value)
)
const canSend = computed(() => question.value.trim().length > 0 && !sending.value)
const serviceText = computed(() => {
  if (serviceOnline.value === true) {
    return '服务在线'
  }
  if (serviceOnline.value === false) {
    return '服务离线'
  }
  return '连接中'
})
const serviceClass = computed(() => ({
  'state-online': serviceOnline.value === true,
  'state-offline': serviceOnline.value === false,
  'state-checking': serviceOnline.value === null
}))

function readStorage(key) {
  try {
    return window.localStorage.getItem(key) || ''
  } catch (error) {
    return ''
  }
}

function writeStorage(key, value) {
  try {
    if (value) {
      window.localStorage.setItem(key, value)
    } else {
      window.localStorage.removeItem(key)
    }
  } catch (error) {
    // 隐私模式下 localStorage 可能不可用，忽略即可。
  }
}

function nextMessageId() {
  messageSequence += 1
  return `${Date.now()}-${messageSequence}`
}

async function scrollToBottom() {
  await nextTick()
  const element = scrollRef.value
  if (!element) {
    return
  }
  if (typeof element.scrollTo === 'function') {
    element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' })
  } else {
    element.scrollTop = element.scrollHeight
  }
}

// token 到达频率可能很高，用 rAF 合并滚动，避免每个 token 都触发一次布局。
function scheduleScroll() {
  if (scrollFrame !== null) {
    return
  }
  scrollFrame = window.requestAnimationFrame(() => {
    scrollFrame = null
    nextTick(() => {
      const element = scrollRef.value
      if (element) {
        element.scrollTop = element.scrollHeight
      }
    })
  })
}

function cancelScheduledScroll() {
  if (scrollFrame !== null) {
    window.cancelAnimationFrame(scrollFrame)
    scrollFrame = null
  }
}

async function loadBootstrap() {
  loadingMeta.value = true
  const [databaseResult, healthResult] = await Promise.allSettled([
    fetchDatabases(),
    fetchHealth()
  ])
  const problems = []

  if (databaseResult.status === 'fulfilled') {
    databases.value = Array.isArray(databaseResult.value) ? databaseResult.value : []
    if (
      selectedDatabase.value &&
      !databases.value.some((database) => database.key === selectedDatabase.value)
    ) {
      selectedDatabase.value = ''
      writeStorage(STORAGE_KEYS.database, '')
    }
  } else {
    problems.push(`数据源加载失败：${databaseResult.reason.message}`)
  }

  const healthUp =
    healthResult.status === 'fulfilled' &&
    healthResult.value &&
    healthResult.value.status === 'UP'
  if (healthUp) {
    serviceOnline.value = true
  } else {
    serviceOnline.value = false
    const reason =
      healthResult.status === 'rejected'
        ? healthResult.reason.message
        : '健康检查返回了异常状态'
    problems.push(`服务健康检查失败：${reason}`)
  }

  pageError.value = problems.join(' ')
  loadingMeta.value = false
}

function resetConversationState() {
  messages.value = []
  conversationId.value = ''
  writeStorage(STORAGE_KEYS.conversationId, '')
}

function parseHistoryResponse(raw) {
  if (!raw) {
    return null
  }
  if (typeof raw === 'object') {
    return raw
  }
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw)
    } catch (error) {
      return null
    }
  }
  return null
}

// 历史消息与实时消息共用 ChatMessage，助手消息补齐 response 结构即可复用渲染。
function toHistoryMessages(detail) {
  const items = []
  const source = detail && Array.isArray(detail.messages) ? detail.messages : []
  for (const entry of source) {
    const time = entry.createdAt || new Date().toISOString()
    const role = String(entry.role || '').toUpperCase()
    if (role === 'USER') {
      items.push({
        id: nextMessageId(),
        role: 'user',
        content: entry.content || '',
        time
      })
      continue
    }
    const response = parseHistoryResponse(entry.response)
    items.push({
      id: nextMessageId(),
      role: 'assistant',
      response: response || { answer: entry.content || '', status: 'SUCCESS' },
      reasoning: (response && response.reasoning) || '',
      streaming: false,
      time
    })
  }
  return items
}

async function loadHistory() {
  if (!currentUser.value) {
    return
  }
  loadingHistory.value = true
  try {
    const data = await fetchConversations()
    historyList.value = Array.isArray(data) ? data : []
  } catch (error) {
    if (error.status !== 401) {
      pageError.value = `历史对话加载失败：${error.message}`
    }
  } finally {
    loadingHistory.value = false
  }
}

async function restoreConversation(targetId, { silent = false } = {}) {
  if (!targetId || sending.value || restoringId.value) {
    return
  }

  if (!silent && targetId !== conversationId.value && messages.value.length) {
    try {
      await ElMessageBox.confirm(
        '当前页面已有对话，恢复历史会话会替换现有消息。是否继续？',
        '恢复历史对话',
        {
          type: 'warning',
          confirmButtonText: '恢复',
          cancelButtonText: '取消',
          distinguishCancelAndClose: true
        }
      )
    } catch (error) {
      return
    }
  }

  restoringId.value = targetId
  try {
    const detail = await fetchConversationDetail(targetId)
    messages.value = toHistoryMessages(detail)
    const nextId = (detail && detail.conversationId) || targetId
    conversationId.value = nextId
    writeStorage(STORAGE_KEYS.conversationId, nextId)
    if (detail && detail.datasourceKey) {
      selectedDatabase.value = detail.datasourceKey
      writeStorage(STORAGE_KEYS.database, detail.datasourceKey)
    }
    await scrollToBottom()
    if (!silent) {
      ElMessage.success('已恢复历史对话')
    }
  } catch (error) {
    if (error.status === 404) {
      historyList.value = historyList.value.filter(
        (item) => item.conversationId !== targetId
      )
      if (conversationId.value === targetId) {
        resetConversationState()
      }
      if (!silent) {
        ElMessage.warning('该历史对话已不存在')
      }
    } else if (!silent && error.status !== 401) {
      ElMessage.error(`恢复历史对话失败：${error.message}`)
    }
  } finally {
    restoringId.value = ''
  }
}

async function removeHistory(targetId) {
  if (!targetId || deletingHistoryId.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '删除后将同时移除该会话的所有消息，且无法恢复。是否继续？',
      '删除历史对话',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )
  } catch (error) {
    return
  }

  deletingHistoryId.value = targetId
  try {
    await deleteHistoryConversation(targetId)
    historyList.value = historyList.value.filter((item) => item.conversationId !== targetId)
    if (conversationId.value === targetId) {
      resetConversationState()
    }
    ElMessage.success('历史对话已删除')
  } catch (error) {
    if (error.status !== 401) {
      ElMessage.error(`删除历史对话失败：${error.message}`)
    }
  } finally {
    deletingHistoryId.value = ''
  }
}

async function loadWorkspace() {
  await loadBootstrap()
  if (!currentUser.value) {
    return
  }
  await loadHistory()
  // 刷新页面后自动恢复最近一次会话，进程重启也不丢上下文。
  if (conversationId.value && !messages.value.length) {
    await restoreConversation(conversationId.value, { silent: true })
  }
}

async function handleAuthenticated(user) {
  authChecking.value = false
  adminMode.value = false
  if (user) {
    currentUser.value = user
  } else {
    try {
      currentUser.value = await fetchCurrentUser()
    } catch (error) {
      currentUser.value = null
      return
    }
  }
  resetConversationState()
  await loadWorkspace()
}

async function handleLogout() {
  if (loggingOut.value) {
    return
  }
  loggingOut.value = true
  try {
    await logout()
  } catch (error) {
    // token 可能已过期，本地登录态仍然要清理。
  } finally {
    clearToken()
    currentUser.value = null
    adminMode.value = false
    resetConversationState()
    historyList.value = []
    databases.value = []
    selectedDatabase.value = ''
    writeStorage(STORAGE_KEYS.database, '')
    serviceOnline.value = null
    pageError.value = ''
    loggingOut.value = false
  }
}

function handleUnauthorized() {
  if (!currentUser.value && !getToken()) {
    return
  }
  currentUser.value = null
  authChecking.value = false
  loggingOut.value = false
  adminMode.value = false
  resetConversationState()
  historyList.value = []
  databases.value = []
  selectedDatabase.value = ''
  writeStorage(STORAGE_KEYS.database, '')
  serviceOnline.value = null
  pageError.value = ''
  ElMessage.warning('登录已过期，请重新登录')
}

async function bootstrapAuth() {
  if (!getToken()) {
    authChecking.value = false
    return
  }
  try {
    currentUser.value = await fetchCurrentUser()
  } catch (error) {
    currentUser.value = null
  } finally {
    authChecking.value = false
  }
}

function useExample(example) {
  question.value = example
  nextTick(() => {
    if (composerRef.value && typeof composerRef.value.focus === 'function') {
      composerRef.value.focus()
    }
  })
}

function handleKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  handleSend()
}

async function handleSend() {
  if (!canSend.value) {
    return
  }

  const text = question.value.trim()
  question.value = ''
  messages.value.push({
    id: nextMessageId(),
    role: 'user',
    content: text,
    time: new Date().toISOString()
  })

  // 先插入占位消息，meta/reasoning 到达后原地更新，避免流式过程中反复重建组件。
  const assistantIndex = messages.value.length
  messages.value.push({
    id: nextMessageId(),
    role: 'assistant',
    response: null,
    reasoning: '',
    streaming: true,
    time: new Date().toISOString()
  })

  sending.value = true
  await scrollToBottom()

  // 首次提问即由前端生成合规会话 id，失败重试时仍能复用同一会话。
  const activeConversationId = conversationId.value || createConversationId()
  conversationId.value = activeConversationId
  writeStorage(STORAGE_KEYS.conversationId, activeConversationId)

  const applyConversationId = (nextId) => {
    if (!nextId) {
      return
    }
    conversationId.value = nextId
    writeStorage(STORAGE_KEYS.conversationId, nextId)
  }

  try {
    const response = await sendQuestion({
      question: text,
      database: selectedDatabase.value,
      conversationId: activeConversationId,
      onMeta: (meta) => {
        const message = messages.value[assistantIndex]
        if (!message) {
          return
        }
        applyConversationId(meta.conversationId)
        message.response = {
          ...meta,
          answer: (message.response && message.response.answer) || ''
        }
        scrollToBottom()
      },
      onReasoning: (chunk) => {
        const message = messages.value[assistantIndex]
        if (!message || !chunk) {
          return
        }
        message.reasoning = `${message.reasoning || ''}${chunk}`
        scheduleScroll()
      },
      onToken: (chunk) => {
        const message = messages.value[assistantIndex]
        if (!message || !chunk) {
          return
        }
        // token 分片边到边拼接，配合 Markdown 渲染实现打字机效果；
        // 后续 meta 事件会保留已生成文本，done 事件再用完整答案覆盖。
        if (!message.response) {
          message.response = { answer: '' }
        }
        message.response.answer = `${message.response.answer || ''}${chunk}`
        scheduleScroll()
      }
    })

    if (!response || !response.conversationId) {
      throw new Error('后端返回了空的响应')
    }

    applyConversationId(response.conversationId)
    const message = messages.value[assistantIndex]
    if (message) {
      message.reasoning = response.reasoning || message.reasoning
      message.response = response
      message.streaming = false
    }
    serviceOnline.value = true
    // 后端已落库，刷新历史列表让新会话/标题立即出现。
    loadHistory()
  } catch (error) {
    const message = messages.value[assistantIndex]
    if (message) {
      message.streaming = false
      message.error = {
        code: error.code || 'ERROR',
        message: error.message || '请求失败，请稍后重试',
        status: error.status || 0
      }
    }
    if (error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT') {
      serviceOnline.value = false
    }
  } finally {
    sending.value = false
    cancelScheduledScroll()
    await scrollToBottom()
  }
}

async function handleClear() {
  if (sending.value || clearing.value) {
    return
  }
  if (!messages.value.length && !conversationId.value) {
    return
  }

  try {
    await ElMessageBox.confirm(
      '将清空当前页面的消息，并重置服务端会话上下文。是否继续？',
      '清空对话',
      {
        type: 'warning',
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )
  } catch (error) {
    return
  }

  clearing.value = true
  const previousConversationId = conversationId.value

  messages.value = []
  conversationId.value = ''
  writeStorage(STORAGE_KEYS.conversationId, '')
  pageError.value = ''

  if (previousConversationId) {
    try {
      await deleteConversation(previousConversationId)
    } catch (error) {
      ElMessage.warning(`本地已清空，但服务端会话清理失败：${error.message}`)
    }
  }

  clearing.value = false
  ElMessage.success('对话已清空')
}

onMounted(async () => {
  window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  await bootstrapAuth()
  if (currentUser.value) {
    await loadWorkspace()
  }
})

onBeforeUnmount(() => {
  cancelScheduledScroll()
  window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
})
</script>
