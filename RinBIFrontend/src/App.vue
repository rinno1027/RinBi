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
          <h1>RinBI {{ isReportMode ? '智能报表' : '数据问答' }}</h1>
          <p>
            {{
              isReportMode
                ? 'Excel 模板 → 只读取数 → 多轮调整报表'
                : '自然语言 → 安全只读 SQL → 可视化结果'
            }}
          </p>
        </div>
      </div>

      <nav class="feature-switch" aria-label="功能切换">
        <button
          type="button"
          :class="{ active: !isReportMode }"
          @click="switchFeature('chat')"
        >
          数据问答
        </button>
        <button
          type="button"
          :class="{ active: isReportMode }"
          @click="switchFeature('report')"
        >
          智能报表
        </button>
      </nav>

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
          @click="toggleAdminMode"
        >
          {{ adminMode ? '返回问答' : '用户管理' }}
        </button>
        <button
          v-if="isAdmin"
          type="button"
          class="admin-toggle-button"
          :class="{ active: dataSourceMode }"
          @click="toggleDataSourceMode"
        >
          {{ dataSourceMode ? '返回问答' : '数据源管理' }}
        </button>
        <button
          type="button"
          class="admin-toggle-button"
          :class="{ active: ruleMode }"
          @click="toggleRuleMode"
        >
          {{ ruleMode ? '返回问答' : '统计规则' }}
        </button>
        <button
          v-if="!adminMode && !dataSourceMode && !ruleMode"
          type="button"
          class="clear-button"
          :disabled="sending || clearing || (!messages.length && !activeConversationId)"
          @click="handleClear"
        >
          {{ clearing ? '清空中…' : isReportMode ? '清空会话' : '清空对话' }}
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

    <DataSourcePanel
      v-else-if="dataSourceMode && isAdmin"
      @close="dataSourceMode = false"
    />

    <RulePanel
      v-else-if="ruleMode"
      :current-user="currentUser"
      @close="handleRulePanelClose"
      @changed="handleRulesChanged"
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

          <div class="rule-picker">
            <div class="rule-picker-head">
              <span class="rule-picker-label">统计规则</span>
              <template v-if="currentDatabase">
                <span class="rule-picker-meta">
                  已选 {{ selectedRuleIds.length }} / {{ ruleOptions.length }}
                </span>
                <button
                  v-if="ruleOptions.length"
                  type="button"
                  class="rule-picker-link"
                  :disabled="sending"
                  @click="toggleAllRules"
                >
                  {{ allRulesSelected ? '清空' : '全选' }}
                </button>
              </template>
              <span v-else class="rule-picker-meta">需指定数据源</span>
            </div>

            <el-select
              v-if="currentDatabase"
              v-model="selectedRuleIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              filterable
              clearable
              size="small"
              class="rule-select"
              :multiple-limit="50"
              :loading="loadingRules"
              :disabled="loadingRules || !ruleOptions.length || sending"
              :placeholder="ruleOptions.length ? '默认选择全部规则' : '该数据源暂无规则'"
            >
              <el-option
                v-for="rule in ruleOptions"
                :key="rule.id"
                :label="rule.ruleName"
                :value="rule.id"
              >
                <span class="rule-option-name">{{ rule.ruleName }}</span>
                <span class="rule-option-code">{{ rule.ruleCode }}</span>
              </el-option>
            </el-select>

            <p v-if="!currentDatabase" class="rule-picker-hint">
              自动选择数据源时不注入统计规则。
            </p>
            <p v-else-if="ruleError" class="rule-picker-hint is-error">
              {{ ruleError }}
              <button type="button" class="rule-picker-link" @click="loadRuleOptions">
                重试
              </button>
            </p>
            <p v-else-if="!loadingRules && !ruleOptions.length" class="rule-picker-hint">
              该数据源暂无统计规则，
              <button type="button" class="rule-picker-link" @click="openRuleManager">
                {{ isAdmin ? '去配置' : '查看规则页' }}
              </button>
            </p>
            <p v-else class="rule-picker-hint">
              仅对本次提问生效，选中规则会以 JSON 注入规划提示词。
            </p>
          </div>

          <p v-if="!loadingMeta && !databases.length" class="panel-hint">
            暂无可用数据源，请检查后端配置。
          </p>
        </section>

        <section v-if="isReportMode" class="panel-card template-panel">
          <div class="panel-title-row">
            <h2 class="panel-title">报表模板</h2>
            <button
              type="button"
              class="panel-refresh"
              :disabled="uploadingTemplate || sending"
              @click="triggerTemplateUpload"
            >
              {{ uploadingTemplate ? '上传中…' : reportTemplate ? '更换模板' : '上传模板' }}
            </button>
          </div>

          <div v-if="reportTemplate" class="template-current">
            <span class="template-name" :title="reportTemplate.templateName">
              {{ reportTemplate.templateName }}
            </span>
            <span class="template-meta">
              本会话仅支持一个模板
              <template v-if="reportTemplate.sheetSummary">
                · {{ reportTemplate.sheetSummary }}
              </template>
            </span>
            <button
              type="button"
              class="template-download"
              :disabled="downloadingTemplate"
              @click="downloadTemplate(reportTemplate)"
            >
              {{ downloadingTemplate ? '下载中…' : '下载模板' }}
            </button>
          </div>
          <p v-else class="panel-hint">
            上传 Excel 模板后即可描述需求，并可在本会话中多轮要求模型调整报表内容。
          </p>

          <p v-if="reportTemplate" class="template-usage">
            上下文占用 {{ reportInputChars }} / {{ reportInputLimit || '—' }} 字符
          </p>

          <div v-if="reportTemplates.length" class="template-history">
            <span class="template-history-title">我导入过的模板</span>
            <ul class="template-list">
              <li
                v-for="item in reportTemplates.slice(0, 6)"
                :key="item.templateId"
                class="template-list-item"
              >
                <button type="button" class="template-main" @click="downloadTemplate(item)">
                  <span class="template-name" :title="item.templateName">
                    {{ item.templateName }}
                  </span>
                  <span class="template-meta">{{ formatDateTime(item.createdAt) }}</span>
                </button>
              </li>
            </ul>
          </div>
        </section>

        <section class="panel-card history-card">
          <div class="panel-title-row">
            <h2 class="panel-title">{{ isReportMode ? '报表会话' : '历史对话' }}</h2>
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
              :class="{ active: item.conversationId === activeConversationId }"
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
                  <span v-if="isReportMode && item.templateName" class="history-tag">
                    {{ item.templateName }}
                  </span>
                  <span v-else-if="item.messageCount">{{ item.messageCount }} 条消息</span>
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
            {{
              loadingHistory
                ? '正在加载…'
                : isReportMode
                  ? '暂无报表会话，上传模板后会自动创建。'
                  : '暂无历史对话，提问后会自动保存。'
            }}
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
          <span class="session-label">{{ isReportMode ? '报表会话' : '当前会话' }}</span>
          <code class="session-id">{{ activeConversationId || '首次使用时自动创建' }}</code>
        </div>
      </aside>

      <main class="chat-panel">
        <div ref="scrollRef" class="chat-scroll">
          <div v-if="!messages.length" class="hero">
            <span class="hero-badge">
              {{ isReportMode ? 'Excel 模板 · 多轮调整 · 只读查询' : '自然语言 · 安全 SQL · 多数据源' }}
            </span>
            <h2>{{ isReportMode ? '上传模板，让模型按需求持续调整报表' : '把业务问题，直接问成数据' }}</h2>
            <p class="hero-description">
              <template v-if="isReportMode">
                先上传 Excel 模板，再描述统计口径与维度；生成报表后可以在同一会话里继续要求
                更换数据源、调整统计规则或修改报表内容，每次都会输出新的 Excel 文件。
              </template>
              <template v-else>
                选择数据源或用自然语言提问。Agent 会自动理解表结构、生成只读查询，
                并把结果整理成表格与分析说明。
              </template>
            </p>
            <div v-if="isReportMode" class="hero-actions">
              <el-button
                type="primary"
                :loading="uploadingTemplate"
                :disabled="sending"
                @click="triggerTemplateUpload"
              >
                {{ reportTemplate ? '新建会话并更换模板' : '上传 Excel 模板' }}
              </el-button>
              <span class="hero-hint">一个会话只对应一个模板，需要更换时请新建会话。</span>
            </div>
            <div v-else class="hero-chips">
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
              <span>{{ isReportMode ? '模板落盘' : '自动选库' }}</span>
              <span>{{ isReportMode ? '多轮调整' : 'SQL 可追溯' }}</span>
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

          <el-alert
            v-if="isReportMode && reportTemplate"
            class="page-alert template-alert"
            type="info"
            :closable="false"
            show-icon
            :title="`当前模板：${reportTemplate.templateName}`"
            :description="`上下文占用 ${reportInputChars} / ${reportInputLimit || '—'} 字符；达到上限后需要新建会话并重新上传模板。`"
          />

          <div class="composer-card">
            <el-input
              ref="composerRef"
              v-model="question"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              resize="none"
              maxlength="2000"
              :placeholder="
                isReportMode
                  ? '描述报表需求或调整点，例如：按月份重排并增加同比列'
                  : '例如：2026 年各产品类别的销售额排名'
              "
              :disabled="sending"
              @keydown="handleKeydown"
            />
            <div class="composer-actions">
              <span v-if="question.length" class="char-count">{{ question.length }} / 2000</span>
              <el-button
                v-if="isReportMode"
                class="report-button"
                :loading="uploadingTemplate"
                :disabled="sending"
                :title="
                  reportTemplate
                    ? '新建报表会话并上传新的 Excel 模板'
                    : '上传 Excel 模板，创建报表会话'
                "
                @click="triggerTemplateUpload"
              >
                {{ reportTemplate ? '新建会话' : '上传模板' }}
              </el-button>
              <el-button
                v-if="sending"
                class="stop-button"
                :loading="stopping"
                title="停止本次生成，已生成的内容会保留在当前页面"
                @click="handleStop"
              >
                {{ stopping ? '停止中…' : '停止' }}
              </el-button>
              <el-button
                v-else
                type="primary"
                class="send-button"
                :disabled="!canSend"
                @click="handleSend"
              >
                发送
              </el-button>
            </div>
          </div>
          <input
            ref="reportInputRef"
            type="file"
            accept=".xlsx,.xls"
            class="report-input"
            @change="handleTemplateFile"
          />

          <div class="composer-tip">
            <span><kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行</span>
            <span v-if="isReportMode">
              模板与报表文件均保存在服务器 · 一个会话一个模板 · 仅执行只读查询
            </span>
            <span v-else>支持左侧勾选统计规则 · 仅执行只读查询</span>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPanel from './components/AdminPanel.vue'
import ChatMessage from './components/ChatMessage.vue'
import DataSourcePanel from './components/DataSourcePanel.vue'
import LoginView from './components/LoginView.vue'
import RulePanel from './components/RulePanel.vue'
import { deleteConversation, fetchDatabases, fetchHealth, sendQuestion, stopConversation } from './api/agent'
import { fetchCurrentUser, logout } from './api/auth'
import {
  continueReport,
  downloadReportTemplate,
  fetchReportTemplates,
  uploadReportTemplate
} from './api/report'
import {
  deleteHistoryConversation,
  fetchConversationDetail,
  fetchConversations
} from './api/history'
import { fetchRuleOptions } from './api/rules'
import { createConversationId, FEATURE, formatDateTime, STORAGE_KEYS } from './utils/format'
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
const dataSourceMode = ref(false)
const ruleMode = ref(false)
const historyList = ref([])
const loadingHistory = ref(false)
const restoringId = ref('')
const deletingHistoryId = ref('')
const messages = ref([])
const question = ref('')
const databases = ref([])
const selectedDatabase = ref(readStorage(STORAGE_KEYS.database))
const ruleOptions = ref([])
const selectedRuleIds = ref([])
const loadingRules = ref(false)
const ruleError = ref('')
const conversationId = ref(readStorage(STORAGE_KEYS.conversationId))
const reportConversationId = ref(readStorage(STORAGE_KEYS.reportConversationId))
const activeFeature = ref(normalizeFeature(readStorage(STORAGE_KEYS.feature)))
const reportTemplate = ref(null)
const reportTemplates = ref([])
const reportInputChars = ref(0)
const reportInputLimit = ref(0)
const uploadingTemplate = ref(false)
const downloadingTemplate = ref(false)
const serviceOnline = ref(null)
const pageError = ref('')
const sending = ref(false)
const stopping = ref(false)
const reporting = ref(false)
const clearing = ref(false)
const loadingMeta = ref(false)
const scrollRef = ref(null)
const composerRef = ref(null)
const reportInputRef = ref(null)

const REPORT_MAX_BYTES = 10 * 1024 * 1024

let messageSequence = 0
let scrollFrame = null
let ruleRequestSequence = 0

const userInitial = computed(() => {
  const name = currentUser.value?.displayName || currentUser.value?.username || '?'
  return name.trim().charAt(0).toUpperCase()
})
const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')
// 'chat' 数据问答 / 'report' 智能报表；两个功能各自维护会话与历史。
const isReportMode = computed(() => activeFeature.value === 'report')
const activeConversationId = computed(() =>
  isReportMode.value ? reportConversationId.value : conversationId.value
)
const currentDatabase = computed(() =>
  databases.value.find((database) => database.key === selectedDatabase.value)
)
const allRulesSelected = computed(
  () =>
    ruleOptions.value.length > 0 &&
    selectedRuleIds.value.length === ruleOptions.value.length
)
const canSend = computed(
  () => question.value.trim().length > 0 && !sending.value && !reporting.value
)
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

/**
 * 按当前数据源拉取对话页可选规则；默认全选。
 * 用请求序号丢弃过期响应，避免快速切换数据源时选项串台。
 */
async function loadRuleOptions() {
  const key = selectedDatabase.value
  const sequence = ++ruleRequestSequence

  if (!key || !currentUser.value) {
    ruleOptions.value = []
    selectedRuleIds.value = []
    ruleError.value = ''
    loadingRules.value = false
    return
  }

  loadingRules.value = true
  ruleError.value = ''
  try {
    const data = await fetchRuleOptions(key)
    if (sequence !== ruleRequestSequence || key !== selectedDatabase.value) {
      return
    }
    ruleOptions.value = Array.isArray(data) ? data.filter((rule) => rule && rule.id != null) : []
    selectedRuleIds.value = ruleOptions.value.map((rule) => rule.id)
  } catch (error) {
    if (sequence !== ruleRequestSequence) {
      return
    }
    ruleOptions.value = []
    selectedRuleIds.value = []
    if (error.status !== 401) {
      ruleError.value = `统计规则加载失败：${error.message}`
    }
  } finally {
    if (sequence === ruleRequestSequence) {
      loadingRules.value = false
    }
  }
}

function toggleAllRules() {
  selectedRuleIds.value = allRulesSelected.value
    ? []
    : ruleOptions.value.map((rule) => rule.id)
}

function toggleRuleMode() {
  ruleMode.value = !ruleMode.value
  if (ruleMode.value) {
    adminMode.value = false
    dataSourceMode.value = false
  } else {
    loadRuleOptions()
  }
}

function toggleAdminMode() {
  adminMode.value = !adminMode.value
  if (adminMode.value) {
    dataSourceMode.value = false
    ruleMode.value = false
  }
}

function toggleDataSourceMode() {
  dataSourceMode.value = !dataSourceMode.value
  if (dataSourceMode.value) {
    adminMode.value = false
    ruleMode.value = false
  }
}

function openRuleManager() {
  adminMode.value = false
  dataSourceMode.value = false
  ruleMode.value = true
}

function handleRulePanelClose() {
  ruleMode.value = false
  loadRuleOptions()
}

function handleRulesChanged() {
  if (selectedDatabase.value) {
    loadRuleOptions()
  }
}

function resetRuleSelection() {
  ruleRequestSequence += 1
  ruleOptions.value = []
  selectedRuleIds.value = []
  ruleError.value = ''
  loadingRules.value = false
}

watch(selectedDatabase, (value) => {
  writeStorage(STORAGE_KEYS.database, value)
  loadRuleOptions()
})

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
  if (currentUser.value) {
    loadRuleOptions()
  }
}

function resetConversationState() {
  messages.value = []
  conversationId.value = ''
  writeStorage(STORAGE_KEYS.conversationId, '')
  reportConversationId.value = ''
  writeStorage(STORAGE_KEYS.reportConversationId, '')
  reportTemplate.value = null
  reportInputChars.value = 0
  reportInputLimit.value = 0
  reporting.value = false
  uploadingTemplate.value = false
}

/**
 * 归一化功能类型：DATA_QA / REPORT 与前端内部使用的 chat / report 互转。
 */
function normalizeFeature(value) {
  return String(value || '').toUpperCase() === FEATURE.report ? 'report' : 'chat'
}

/**
 * 清空当前功能的会话；只影响当前功能，另一个功能的会话保持不变。
 */
function resetActiveConversation() {
  messages.value = []
  if (isReportMode.value) {
    reportConversationId.value = ''
    writeStorage(STORAGE_KEYS.reportConversationId, '')
    reportTemplate.value = null
    reportInputChars.value = 0
    reportInputLimit.value = 0
  } else {
    conversationId.value = ''
    writeStorage(STORAGE_KEYS.conversationId, '')
  }
}

/**
 * 切换数据问答 / 智能报表：两个功能各自维护会话 id、历史列表与消息。
 */
async function switchFeature(feature) {
  const next = feature === 'report' ? 'report' : 'chat'
  if (next === activeFeature.value) {
    return
  }
  if (sending.value) {
    ElMessage.warning('当前请求尚未完成，请稍后再切换功能')
    return
  }
  activeFeature.value = next
  writeStorage(STORAGE_KEYS.feature, FEATURE[next])
  adminMode.value = false
  dataSourceMode.value = false
  ruleMode.value = false
  messages.value = []
  pageError.value = ''
  historyList.value = []
  reportTemplate.value = null
  reportInputChars.value = 0
  reportInputLimit.value = 0
  await loadHistory()
  if (next === 'report') {
    await loadReportTemplates()
    if (reportConversationId.value) {
      await restoreConversation(reportConversationId.value, { silent: true })
    }
  } else if (conversationId.value) {
    await restoreConversation(conversationId.value, { silent: true })
  }
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
    const data = await fetchConversations(50, FEATURE[activeFeature.value])
    historyList.value = Array.isArray(data) ? data : []
  } catch (error) {
    if (error.status !== 401) {
      pageError.value = `${isReportMode.value ? '报表会话' : '历史对话'}加载失败：${error.message}`
    }
  } finally {
    loadingHistory.value = false
  }
}

/**
 * 加载当前用户导入过的模板，便于随时重新下载。
 */
async function loadReportTemplates() {
  if (!currentUser.value) {
    return
  }
  try {
    const data = await fetchReportTemplates()
    reportTemplates.value = Array.isArray(data)
      ? data.filter((item) => item && item.templateId)
      : []
  } catch (error) {
    if (error.status !== 401) {
      pageError.value = `模板列表加载失败：${error.message}`
    }
  }
}

async function restoreConversation(targetId, { silent = false } = {}) {
  // 静默恢复用于上传模板后立即回填消息，此时 sending 会被占用，不能用它拦截
  if (!targetId || restoringId.value || (!silent && sending.value)) {
    return
  }

  if (!silent && targetId !== activeConversationId.value && messages.value.length) {
    try {
      await ElMessageBox.confirm(
        '当前页面已有内容，恢复历史会话会替换现有消息。是否继续？',
        isReportMode.value ? '恢复报表会话' : '恢复历史对话',
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
    const feature = normalizeFeature(detail && detail.featureType)
    if (feature !== activeFeature.value) {
      // 历史列表已按功能过滤，这里只做兜底，避免两个功能的消息串到同一页
      activeFeature.value = feature
      writeStorage(STORAGE_KEYS.feature, FEATURE[feature])
    }
    messages.value = toHistoryMessages(detail)
    const nextId = (detail && detail.conversationId) || targetId
    if (feature === 'report') {
      reportConversationId.value = nextId
      writeStorage(STORAGE_KEYS.reportConversationId, nextId)
      reportTemplate.value = (detail && detail.template) || null
      reportInputChars.value = Number(detail && detail.inputChars) || 0
      reportInputLimit.value = Number(detail && detail.inputLimit) || 0
    } else {
      conversationId.value = nextId
      writeStorage(STORAGE_KEYS.conversationId, nextId)
    }
    if (detail && detail.datasourceKey) {
      selectedDatabase.value = detail.datasourceKey
      writeStorage(STORAGE_KEYS.database, detail.datasourceKey)
    }
    await scrollToBottom()
    if (!silent) {
      ElMessage.success(isReportMode.value ? '已恢复报表会话' : '已恢复历史对话')
    }
  } catch (error) {
    if (error.status === 404) {
      historyList.value = historyList.value.filter(
        (item) => item.conversationId !== targetId
      )
      if (activeConversationId.value === targetId) {
        resetActiveConversation()
      }
      if (!silent) {
        ElMessage.warning('该会话已不存在')
      }
    } else if (!silent && error.status !== 401) {
      ElMessage.error(`恢复会话失败：${error.message}`)
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
    if (activeConversationId.value === targetId) {
      resetActiveConversation()
    }
    ElMessage.success(isReportMode.value ? '报表会话已删除' : '历史对话已删除')
  } catch (error) {
    if (error.status !== 401) {
      ElMessage.error(`删除会话失败：${error.message}`)
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
  if (isReportMode.value) {
    await loadReportTemplates()
  }
  // 刷新页面后自动恢复当前功能最近一次会话，进程重启也不丢上下文。
  if (activeConversationId.value && !messages.value.length) {
    await restoreConversation(activeConversationId.value, { silent: true })
  }
}

async function handleAuthenticated(user) {
  authChecking.value = false
  adminMode.value = false
  dataSourceMode.value = false
  ruleMode.value = false
  resetRuleSelection()
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
    dataSourceMode.value = false
    ruleMode.value = false
    resetRuleSelection()
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
  dataSourceMode.value = false
  ruleMode.value = false
  resetRuleSelection()
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

/**
 * 把最后一条仍在生成的助手消息标记为“已停止”。
 *
 * <p>停止时后端不保存本轮记忆与历史，已生成的部分内容只保留在当前页面，
 * 方便用户确认已经输出的内容。</p>
 */
function markStreamingStopped() {
  const message = messages.value[messages.value.length - 1]
  if (!message || message.role !== 'assistant' || !message.streaming) {
    return
  }
  message.streaming = false
  message.reporting = false
  message.stopped = true
}

/**
 * 停止当前正在生成的数据问答 / 智能报表。
 *
 * <p>先请求后端取消任务（中断模型流式调用与数据库查询），随后立即把消息标记为已停止，
 * 让“停止”按钮的反馈不依赖 SSE 连接何时关闭。</p>
 */
async function handleStop() {
  if (!sending.value || stopping.value) {
    return
  }
  stopping.value = true
  try {
    await stopConversation(activeConversationId.value)
    ElMessage.info('已停止生成')
  } finally {
    stopping.value = false
  }
  markStreamingStopped()
}

/**
 * 页面关闭 / 刷新时尽力取消后端任务。
 *
 * <p>使用 keepalive 让请求在页面卸载后仍能发出，避免浏览器已断开连接、
 * 后端却继续消耗模型 token 与数据库连接。</p>
 */
function stopOnUnload() {
  if (!sending.value) {
    return
  }
  stopConversation(activeConversationId.value, { keepalive: true })
}

async function handleSend() {
  if (!canSend.value) {
    return
  }

  if (isReportMode.value) {
    await handleReportMessage()
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
  const chatConversationId = conversationId.value || createConversationId()
  conversationId.value = chatConversationId
  writeStorage(STORAGE_KEYS.conversationId, chatConversationId)

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
      ruleIds: selectedDatabase.value ? [...selectedRuleIds.value] : [],
      conversationId: chatConversationId,
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
      // 极端竞态：点击“停止”的瞬间后端其实已完成，此时按正常完成展示。
      message.stopped = false
    }
    serviceOnline.value = true
    // 后端已落库，刷新历史列表让新会话/标题立即出现。
    loadHistory()
  } catch (error) {
    const message = messages.value[assistantIndex]
    if (error && error.code === 'STOPPED') {
      // 用户主动停止：保留已生成的部分内容，不作为错误展示，也不刷新历史。
      if (message) {
        message.streaming = false
        message.stopped = true
      }
      return
    }
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

/**
 * 触发模板上传：当前会话已有模板时先确认新建会话，避免违反“一个会话一个模板”。
 */
async function triggerTemplateUpload() {
  if (sending.value || uploadingTemplate.value) {
    return
  }
  if (!isReportMode.value) {
    await switchFeature('report')
  }
  if (reportTemplate.value) {
    try {
      await ElMessageBox.confirm(
        '当前报表会话已导入模板，一个会话只支持一个模板。上传新模板将新建会话，原会话历史会保留。是否继续？',
        '新建报表会话',
        {
          type: 'warning',
          confirmButtonText: '新建并上传',
          cancelButtonText: '取消',
          distinguishCancelAndClose: true
        }
      )
    } catch (error) {
      return
    }
    messages.value = []
    reportConversationId.value = ''
    writeStorage(STORAGE_KEYS.reportConversationId, '')
    reportTemplate.value = null
    reportInputChars.value = 0
    reportInputLimit.value = 0
  }
  const input = reportInputRef.value
  if (!input) {
    return
  }
  // 允许连续选择同一个文件重新上传
  input.value = ''
  input.click()
}

async function handleTemplateFile(event) {
  const input = event && event.target
  const file = input && input.files && input.files[0] ? input.files[0] : null
  if (input) {
    input.value = ''
  }
  if (!file) {
    return
  }
  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    ElMessage.error('仅支持 .xlsx 或 .xls 格式的 Excel 模板')
    return
  }
  if (file.size > REPORT_MAX_BYTES) {
    ElMessage.error('模板文件不能超过 10MB')
    return
  }

  const targetConversationId = reportConversationId.value || createConversationId('rep')
  uploadingTemplate.value = true
  sending.value = true
  try {
    const info = await uploadReportTemplate({
      file,
      conversationId: targetConversationId
    })
    reportConversationId.value = (info && info.conversationId) || targetConversationId
    writeStorage(STORAGE_KEYS.reportConversationId, reportConversationId.value)
    reportTemplate.value = info || null
    serviceOnline.value = true
    // 以服务端记录为准恢复消息，保证刷新页面后与当前页面一致
    await restoreConversation(reportConversationId.value, { silent: true })
    await loadReportTemplates()
    await loadHistory()
    ElMessage.success('模板已导入，请描述报表需求')
  } catch (error) {
    if (error.status !== 401) {
      ElMessage.error(`模板导入失败：${error.message}`)
    }
    if (error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT') {
      serviceOnline.value = false
    }
  } finally {
    uploadingTemplate.value = false
    sending.value = false
  }
}

/**
 * 在报表会话中生成或调整报表：复用会话模板，按本轮需求重新规划取数逻辑。
 */
async function handleReportMessage() {
  if (!reportTemplate.value && !reportConversationId.value) {
    ElMessage.warning('请先上传 Excel 模板，创建报表会话')
    triggerTemplateUpload()
    return
  }
  const text = question.value.trim()
  if (!text) {
    return
  }
  question.value = ''
  messages.value.push({
    id: nextMessageId(),
    role: 'user',
    content: text,
    time: new Date().toISOString()
  })
  const assistantIndex = messages.value.length
  messages.value.push({
    id: nextMessageId(),
    role: 'assistant',
    response: null,
    reasoning: '',
    streaming: true,
    reporting: true,
    time: new Date().toISOString()
  })

  sending.value = true
  reporting.value = true
  await scrollToBottom()

  try {
    const response = await continueReport({
      conversationId: reportConversationId.value,
      templateId: reportTemplate.value ? reportTemplate.value.templateId : null,
      question: text,
      database: selectedDatabase.value,
      // 与数据问答共用左侧规则选择：仅在指定数据源时注入勾选的统计规则
      ruleIds: selectedDatabase.value ? [...selectedRuleIds.value] : []
    })
    if (response && response.conversationId) {
      reportConversationId.value = response.conversationId
      writeStorage(STORAGE_KEYS.reportConversationId, response.conversationId)
    }
    if (response && response.template) {
      reportTemplate.value = response.template
    }
    if (response && Number.isFinite(response.inputChars)) {
      reportInputChars.value = response.inputChars
    }
    if (response && Number.isFinite(response.inputLimit)) {
      reportInputLimit.value = response.inputLimit
    }
    const message = messages.value[assistantIndex]
    if (message) {
      message.reasoning = (response && response.reasoning) || ''
      message.response = response
      message.streaming = false
      message.reporting = false
      // 极端竞态：点击“停止”的瞬间报表其实已完成，此时按正常完成展示。
      message.stopped = false
    }
    serviceOnline.value = true
    await loadHistory()
    await loadReportTemplates()
  } catch (error) {
    const message = messages.value[assistantIndex]
    if (error && error.code === 'STOPPED') {
      // 用户主动停止报表生成：本轮不会保存报表与历史，也不占用会话输入预算。
      if (message) {
        message.streaming = false
        message.reporting = false
        message.stopped = true
      }
      return
    }
    if (message) {
      message.streaming = false
      message.reporting = false
      message.error = {
        code: error.code || 'ERROR',
        message: error.message || '报表生成失败，请稍后重试',
        status: error.status || 0
      }
    }
    if (error.code === 'INPUT_LIMIT') {
      // 累计输入达到模型上限：提示新建会话并重新上传模板
      pageError.value =
        error.message ||
        '当前报表会话已达到模型输入上限，请新建会话并重新上传模板。'
      ElMessage.warning('当前会话已达模型输入上限，请新建会话并重新上传模板')
    }
    if (error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT') {
      serviceOnline.value = false
    }
  } finally {
    sending.value = false
    reporting.value = false
    cancelScheduledScroll()
    await scrollToBottom()
  }
}

/**
 * 下载模板：当前会话模板或“我的模板”列表项。
 */
async function downloadTemplate(item) {
  const target = item || reportTemplate.value
  if (!target || !target.templateId || downloadingTemplate.value) {
    return
  }
  downloadingTemplate.value = true
  try {
    await downloadReportTemplate({
      templateId: target.templateId,
      templateName: target.templateName
    })
    ElMessage.success('模板已开始下载')
  } catch (error) {
    ElMessage.error(
      error && error.message ? `模板下载失败：${error.message}` : '模板下载失败，请稍后重试'
    )
  } finally {
    downloadingTemplate.value = false
  }
}


async function handleClear() {
  if (sending.value || clearing.value) {
    return
  }
  if (!messages.value.length && !activeConversationId.value) {
    return
  }

  try {
    await ElMessageBox.confirm(
      isReportMode.value
        ? '将清空当前报表会话的消息并重置服务端上下文；已保存的会话历史与模板仍可在左侧列表中找到。是否继续？'
        : '将清空当前页面的消息，并重置服务端会话上下文。是否继续？',
      isReportMode.value ? '清空报表会话' : '清空对话',
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
  const previousConversationId = activeConversationId.value

  resetActiveConversation()
  pageError.value = ''

  if (previousConversationId) {
    try {
      await deleteConversation(previousConversationId)
    } catch (error) {
      ElMessage.warning(`本地已清空，但服务端会话清理失败：${error.message}`)
    }
  }
  if (isReportMode.value) {
    await loadReportTemplates()
  }
  await loadHistory()

  clearing.value = false
  ElMessage.success(isReportMode.value ? '报表会话已清空' : '对话已清空')
}

onMounted(async () => {
  window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  // 关闭 / 刷新页面时尽力让后端停止正在执行的任务（keepalive 请求）。
  window.addEventListener('beforeunload', stopOnUnload)
  await bootstrapAuth()
  if (currentUser.value) {
    await loadWorkspace()
  }
})

onBeforeUnmount(() => {
  stopOnUnload()
  cancelScheduledScroll()
  window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  window.removeEventListener('beforeunload', stopOnUnload)
})
</script>
