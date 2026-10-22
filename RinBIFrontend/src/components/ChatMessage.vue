<template>
  <article class="message" :class="`message-${message.role}`">
    <div class="message-avatar" :class="message.role === 'user' ? 'avatar-user' : 'avatar-agent'">
      {{ message.role === 'user' ? '我' : 'R' }}
    </div>

    <div class="message-main">
      <template v-if="message.role === 'user'">
        <div class="user-bubble">{{ message.content }}</div>
        <div class="message-time">{{ formatTime(message.time) }}</div>
      </template>

      <template v-else>
        <div class="agent-card">
          <header class="agent-head">
            <span class="agent-name">RinBI Agent</span>
            <el-tag v-if="isStreaming" size="small" type="info" effect="light">
              {{ isThinking ? '思考中' : '生成中' }}
            </el-tag>
            <el-tag v-else-if="status" size="small" :type="meta.type" effect="light">
              {{ meta.label }}
            </el-tag>
            <span class="message-time">{{ formatTime(message.time) }}</span>
          </header>

          <el-alert
            v-if="message.error"
            type="error"
            :closable="false"
            show-icon
            title="请求失败"
          >
            <p class="error-message">{{ message.error.message }}</p>
            <p v-if="errorDetail" class="error-code">{{ errorDetail }}</p>
          </el-alert>

          <div v-if="isStreaming && !answerHtml && !hasReasoning" class="stream-placeholder">
            <span class="typing-dots"><i></i><i></i><i></i></span>
            <span>
              {{ response.sql ? '正在连接推理模型…' : '正在理解问题、规划并执行只读查询…' }}
            </span>
          </div>

          <div v-if="hasReasoning" class="reasoning-block">
            <div class="reasoning-head">
              <button
                type="button"
                class="reasoning-toggle"
                :aria-expanded="reasoningOpen"
                @click="reasoningOpen = !reasoningOpen"
              >
                <span class="chevron" :class="{ open: reasoningOpen }">›</span>
                {{ reasoningOpen ? '收起思考过程' : '查看思考过程' }}
              </button>
              <span class="reasoning-state">
                <template v-if="isThinking">
                  <span class="typing-dots"><i></i><i></i><i></i></span>
                  思考中
                </template>
                <template v-else>已思考</template>
              </span>
            </div>
            <pre
              v-show="reasoningOpen"
              ref="reasoningEl"
              class="reasoning-text"
              :class="{ 'is-streaming': isStreaming }"
            >{{ reasoningText }}</pre>
          </div>

          <template v-if="hasResponse">
            <el-alert
              v-if="showReasonAlert"
              :type="reasonAlertType"
              :title="meta.label"
              :description="response.reason"
              :closable="false"
              show-icon
              class="reason-alert"
            />

            <div
              v-if="answerHtml"
              class="markdown-body"
              :class="{ 'is-streaming': isStreaming }"
              v-html="answerHtml"
            ></div>

            <ChartPanel
              v-if="charts.length"
              ref="chartPanelRef"
              :charts="charts"
              :table="response.table"
            />

            <div v-if="canExport" class="answer-actions">
              <button
                type="button"
                class="answer-action-button"
                :disabled="exporting"
                :title="exporting ? '正在生成 PDF…' : '导出回答正文与图表为 PDF'"
                @click="handleExportPdf"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M12 3v11" />
                  <path d="m7.5 9.5 4.5 4.5 4.5-4.5" />
                  <path d="M4 17.5v1.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1.5" />
                </svg>
                <span>{{ exporting ? '正在导出…' : '导出 PDF' }}</span>
              </button>
            </div>

            <el-alert
              v-for="(warning, index) in warnings"
              :key="`${warning}-${index}`"
              type="warning"
              :title="warning"
              :closable="false"
              show-icon
              class="warning-alert"
            />

            <div v-if="hasMeta" class="agent-meta">
              <span v-if="databaseLabel" class="meta-item">
                数据源 <strong>{{ databaseLabel }}</strong>
              </span>
              <span v-if="rowCount !== null" class="meta-item">
                返回 <strong>{{ rowCount }}</strong> 行
              </span>
              <span v-if="response.truncated" class="meta-item meta-warning">结果已截断</span>
              <span v-if="response.elapsedMs !== null && response.elapsedMs !== undefined" class="meta-item">
                耗时 <strong>{{ formatElapsed(response.elapsedMs) }}</strong>
              </span>
            </div>

            <div v-if="response.sql" class="sql-block">
              <div class="sql-head">
                <button type="button" class="sql-toggle" @click="sqlOpen = !sqlOpen">
                  <span class="chevron" :class="{ open: sqlOpen }">›</span>
                  {{ sqlOpen ? '收起执行 SQL' : '查看执行 SQL' }}
                </button>
                <button type="button" class="sql-copy" @click="copySql">复制</button>
              </div>
              <pre v-show="sqlOpen" class="sql-code"><code>{{ response.sql }}</code></pre>
            </div>

            <QueryResultTable
              v-if="showResultTable"
              :table="response.table"
              :row-count="rowCount"
              :truncated="Boolean(response.truncated)"
            />
          </template>
        </div>
      </template>
    </div>
  </article>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QueryResultTable from './QueryResultTable.vue'
import { renderMarkdown } from '../utils/markdown'
import { exportAnswerPdf } from '../utils/pdf'
import { formatElapsed, formatTime, statusMeta } from '../utils/format'

// ECharts 体积较大，仅在本次回答包含图表时才加载对应 chunk。
const ChartPanel = defineAsyncComponent(() => import('./ChartPanel.vue'))

const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

const sqlOpen = ref(false)
const reasoningOpen = ref(true)
const reasoningEl = ref(null)
const chartPanelRef = ref(null)
const exporting = ref(false)

const response = computed(() => props.message.response || {})
const isStreaming = computed(() => Boolean(props.message.streaming))
const reasoningText = computed(() =>
  String(props.message.reasoning || response.value.reasoning || '')
)
const hasReasoning = computed(() => Boolean(reasoningText.value.trim()))
const hasResponse = computed(() => Boolean(props.message.response))
const status = computed(() => response.value.status || '')
const meta = computed(() => statusMeta(status.value))
const warnings = computed(() =>
  Array.isArray(response.value.warnings) ? response.value.warnings.filter(Boolean) : []
)
const charts = computed(() =>
  Array.isArray(response.value.charts) ? response.value.charts : []
)
const rowCount = computed(() => {
  const value = response.value.rowCount
  return Number.isFinite(value) ? value : null
})
const answerHtml = computed(() => renderMarkdown(response.value.answer))
// 导出按钮放在回答正文与图表的左下方；流式生成中或没有正文/图表时不显示。
const canExport = computed(
  () => !isStreaming.value && Boolean(answerHtml.value || charts.value.length)
)
// meta 事件会先于答案分片下发 table；流式期间暂不渲染数据明细，
// 否则表格高度会把滚动容器一直推到底部，导致正在生成的答案不可见。
const showResultTable = computed(
  () => !isStreaming.value && Boolean(response.value.table)
)
// token 分片开始后即进入“生成中”，与仅思考阶段区分展示
const isThinking = computed(() => isStreaming.value && !answerHtml.value)
const showReasonAlert = computed(
  () =>
    Boolean(response.value.reason) &&
    response.value.reason !== response.value.answer &&
    status.value !== 'SUCCESS'
)
// el-alert 只接受 success / warning / info / error，danger 需要映射为 error
const reasonAlertType = computed(() => {
  const type = meta.value.type
  if (type === 'danger') {
    return 'error'
  }
  return type === 'success' ? 'info' : type
})
const databaseLabel = computed(
  () => response.value.databaseName || response.value.database || ''
)
const hasMeta = computed(
  () =>
    Boolean(databaseLabel.value) ||
    rowCount.value !== null ||
    Boolean(response.value.truncated) ||
    (response.value.elapsedMs !== null && response.value.elapsedMs !== undefined)
)
const errorDetail = computed(() => {
  if (!props.message.error) {
    return ''
  }
  const code = props.message.error.code
  const httpStatus = props.message.error.status
  if (code && httpStatus) {
    return `错误码：${code} · HTTP ${httpStatus}`
  }
  if (code) {
    return `错误码：${code}`
  }
  return httpStatus ? `HTTP ${httpStatus}` : ''
})

// 思考结束后自动收起，保留入口方便用户回看。
watch(isStreaming, (streaming, wasStreaming) => {
  if (wasStreaming && !streaming && hasReasoning.value) {
    reasoningOpen.value = false
  }
})

// 答案开始逐片输出后也自动收起思考过程，避免遮挡正文。
watch(isThinking, (thinking, wasThinking) => {
  if (wasThinking && !thinking && hasReasoning.value) {
    reasoningOpen.value = false
  }
})

// 思考过程持续增长时保持滚动到底部，便于看到最新推理。
watch(reasoningText, async () => {
  if (!reasoningOpen.value || !isStreaming.value) {
    return
  }
  await nextTick()
  const el = reasoningEl.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
})

// ChartPanel 为异步组件，回答刚结束时可能尚未加载完成，短暂等待可避免导出遗漏图表。
const CHART_PANEL_WAIT_TIMES = 20
const CHART_PANEL_WAIT_INTERVAL = 100

function waitForChartPanel() {
  if (!charts.value.length) {
    return Promise.resolve(null)
  }
  return new Promise((resolve) => {
    let remaining = CHART_PANEL_WAIT_TIMES
    const check = () => {
      if (chartPanelRef.value || remaining <= 0) {
        resolve(chartPanelRef.value || null)
        return
      }
      remaining -= 1
      window.setTimeout(check, CHART_PANEL_WAIT_INTERVAL)
    }
    check()
  })
}

async function handleExportPdf() {
  if (exporting.value || !canExport.value) {
    return
  }
  exporting.value = true
  try {
    // 等待图表组件渲染完成，确保能取到画布位图。
    await nextTick()
    const chartPanel = await waitForChartPanel()
    const chartImages =
      chartPanel && typeof chartPanel.getChartImages === 'function'
        ? chartPanel.getChartImages()
        : []
    await exportAnswerPdf({
      answerHtml: answerHtml.value,
      charts: chartImages
    })
    ElMessage.success('PDF 已导出')
  } catch (error) {
    console.error('[RinBI] 导出 PDF 失败', error)
    ElMessage.error(error && error.message ? `导出失败：${error.message}` : '导出 PDF 失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

async function copySql() {
  const sql = response.value.sql
  if (!sql) {
    return
  }
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(sql)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = sql
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success('SQL 已复制')
  } catch (error) {
    ElMessage.error('复制失败，请手动选择 SQL')
  }
}
</script>
