<template>
  <section class="admin-page rules-page">
    <div class="admin-toolbar">
      <span class="admin-toolbar-hint">
        统计规则按数据源隔离：所有用户可查看并在提问时使用已授权数据源下的规则，仅管理员可新增、修改或删除。
      </span>
      <div class="admin-toolbar-actions">
        <el-button size="small" :loading="loadingRules" @click="loadRules(rulePage)">刷新数据</el-button>
        <el-button size="small" type="primary" plain @click="$emit('close')">返回问答</el-button>
      </div>
    </div>

    <el-alert
      v-if="pageError"
      class="admin-alert"
      type="warning"
      :title="pageError"
      show-icon
      closable
      @close="pageError = ''"
    />

    <section class="admin-card">
      <div class="admin-filter-bar">
        <div class="admin-filter-left">
          <el-select
            v-model="query.dataSourceKey"
            size="small"
            clearable
            class="rule-source-select"
            placeholder="全部数据源"
            @change="loadRules(1)"
          >
            <el-option
              v-for="source in dataSources"
              :key="source.key"
              :label="source.name"
              :value="source.key"
            />
          </el-select>
          <el-input
            v-model="query.keyword"
            size="small"
            clearable
            class="admin-search"
            placeholder="搜索规则名称或代码"
            @keyup.enter="loadRules(1)"
            @clear="loadRules(1)"
          />
        </div>
        <div class="admin-filter-right">
          <span class="admin-total">共 {{ ruleTotal }} 条规则</span>
          <el-button v-if="isAdmin" type="primary" size="small" @click="openCreate">新增规则</el-button>
        </div>
      </div>

      <el-table
        v-loading="loadingRules"
        :data="rules"
        size="small"
        class="admin-table"
        empty-text="当前数据源下还没有统计规则"
      >
        <el-table-column label="规则" min-width="190">
          <template #default="{ row }">
            <div class="rule-name">{{ row.ruleName }}</div>
            <div class="rule-code">{{ row.ruleCode }} · v{{ row.versionNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数据源" width="145">
          <template #default="{ row }">
            <div class="rule-source-name">{{ row.dataSourceName || row.dataSourceKey }}</div>
            <div class="rule-code">{{ row.dataSourceKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="规则说明" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ruleDescription || '—' }}</template>
        </el-table-column>
        <el-table-column label="统计口径" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="rule-formula">{{ row.formula || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="维护人" width="124">
          <template #default="{ row }">
            <div class="rule-owner">{{ row.updatedByName || row.createdByName || '—' }}</div>
            <div class="rule-code">{{ formatDateTime(row.updatedAt) || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="132" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">
              {{ isAdmin && row.canEdit ? '编辑' : '查看' }}
            </el-button>
            <el-button
              v-if="isAdmin && row.canEdit"
              link
              type="danger"
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-pagination">
        <el-pagination
          v-model:current-page="rulePage"
          v-model:page-size="ruleSize"
          small
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="ruleTotal"
          @current-change="loadRules"
          @size-change="handleSizeChange"
        />
      </div>
    </section>


    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="min(860px, 94vw)"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="dialogLoading">
        <p v-if="ruleMeta" class="rule-meta">
          <span>创建人：{{ ruleMeta.createdByName || '—' }}</span>
          <span>版本：v{{ ruleMeta.versionNo }}</span>
          <span>更新：{{ formatDateTime(ruleMeta.updatedAt) || '—' }}</span>
        </p>
        <el-form label-position="top" class="admin-ds-form">
          <div class="admin-form-grid">
            <el-form-item label="数据源" required>
              <el-select
                v-model="form.dataSourceKey"
                class="admin-full"
                :disabled="readOnly"
                placeholder="选择规则所属数据源"
              >
                <el-option
                  v-for="source in dataSources"
                  :key="source.key"
                  :label="source.name"
                  :value="source.key"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="规则代码" required>
              <el-input
                v-model="form.ruleCode"
                maxlength="50"
                :disabled="readOnly"
                placeholder="全局唯一，例如 GMV_CNY"
              />
            </el-form-item>
            <el-form-item label="规则名称" required class="admin-span-2">
              <el-input
                v-model="form.ruleName"
                maxlength="128"
                :disabled="readOnly"
                placeholder="面向业务的指标名称，例如 有效成交额"
              />
            </el-form-item>
            <el-form-item label="规则说明" class="admin-span-2">
              <el-input
                v-model="form.ruleDescription"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 3 }"
                maxlength="512"
                show-word-limit
                :disabled="readOnly"
                placeholder="说明规则适用的业务对象、指标口径或使用场景"
              />
            </el-form-item>
            <el-form-item label="计算公式 / 统计口径" class="admin-span-2">
              <el-input
                v-model="form.formula"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 4 }"
                maxlength="1024"
                show-word-limit
                :disabled="readOnly"
                placeholder="例如 SUM(sales_order.amount) WHERE status = 'PAID'"
              />
            </el-form-item>
            <el-form-item label="业务规则" class="admin-span-2">
              <el-input
                v-model="form.businessRule"
                type="textarea"
                :autosize="{ minRows: 3, maxRows: 6 }"
                maxlength="4000"
                show-word-limit
                :disabled="readOnly"
                placeholder="过滤条件、时间范围、排除项、去重方式、分组维度等口径约束"
              />
            </el-form-item>
            <el-form-item label="备注" class="admin-span-2">
              <el-input
                v-model="form.remark"
                maxlength="512"
                :disabled="readOnly"
                placeholder="可选，补充说明或维护约定"
              />
            </el-form-item>
          </div>

          <el-divider content-position="left">输入字段</el-divider>

          <div class="rule-fields-head">
            <span class="rule-fields-hint">
              描述规则依赖的表与字段，帮助模型把业务口径映射到真实 schema。
            </span>
            <el-button
              v-if="!readOnly"
              size="small"
              plain
              :disabled="form.inputFields.length >= 50"
              @click="addField"
            >
              添加字段
            </el-button>
          </div>

          <div v-if="form.inputFields.length" class="rule-field-list">
            <div
              v-for="(field, index) in form.inputFields"
              :key="index"
              class="rule-field-row"
            >
              <el-input
                v-model="field.sourceTable"
                maxlength="128"
                :disabled="readOnly"
                placeholder="来源表，例如 sales_order"
              />
              <el-input
                v-model="field.fieldName"
                maxlength="128"
                :disabled="readOnly"
                placeholder="字段名，例如 amount"
              />
              <el-input
                v-model="field.fieldDescription"
                maxlength="512"
                :disabled="readOnly"
                placeholder="字段说明（可选）"
              />
              <el-button
                v-if="!readOnly"
                link
                type="danger"
                @click="removeField(index)"
              >
                删除
              </el-button>
            </div>
          </div>
          <p v-else class="rule-fields-empty">暂未配置输入字段，模型将仅依据公式与业务规则推断。</p>
        </el-form>
      </div>

      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">
          {{ readOnly ? '关闭' : '取消' }}
        </el-button>
        <el-button
          v-if="!readOnly"
          type="primary"
          :loading="saving"
          @click="saveRule"
        >
          {{ editingId ? '保存修改' : '创建规则' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchDatabases } from '../api/agent'
import {
  createRule,
  deleteRule,
  fetchRule,
  fetchRules,
  updateRule
} from '../api/rules'
import { formatDateTime } from '../utils/format'

const emit = defineEmits(['close', 'changed'])

const props = defineProps({
  currentUser: {
    type: Object,
    default: null
  }
})

const rules = ref([])
const ruleTotal = ref(0)
const rulePage = ref(1)
const ruleSize = ref(10)
const query = reactive({ dataSourceKey: '', keyword: '' })
const loadingRules = ref(false)

const dataSources = ref([])
const pageError = ref('')
const isAdmin = computed(
  () => String(props.currentUser?.role || '').toUpperCase() === 'ADMIN'
)

const dialogVisible = ref(false)
const dialogLoading = ref(false)
const editingId = ref(null)
const readOnly = ref(false)
const ruleMeta = ref(null)
const saving = ref(false)

const form = reactive(emptyForm())

const dialogTitle = computed(() => {
  if (!editingId.value) {
    return '新增统计规则'
  }
  return readOnly.value ? '查看统计规则' : '编辑统计规则'
})

function emptyForm() {
  return {
    dataSourceKey: '',
    ruleCode: '',
    ruleName: '',
    ruleDescription: '',
    formula: '',
    businessRule: '',
    remark: '',
    inputFields: []
  }
}

function resetForm(values) {
  Object.assign(form, emptyForm(), values || {})
}

function reportError(error, fallback) {
  if (error?.status === 401) {
    return
  }
  const message = error?.message || fallback
  pageError.value = message
  ElMessage.error(message)
}

async function loadDataSources() {
  try {
    const data = await fetchDatabases()
    dataSources.value = Array.isArray(data) ? data : []
  } catch (error) {
    reportError(error, '数据源加载失败')
  }
}

async function loadRules(page = rulePage.value) {
  rulePage.value = Math.max(1, Number(page) || 1)
  loadingRules.value = true
  try {
    const data = await fetchRules({
      page: rulePage.value,
      size: ruleSize.value,
      dataSourceKey: query.dataSourceKey || undefined,
      keyword: query.keyword.trim() || undefined
    })
    rules.value = Array.isArray(data?.items) ? data.items : []
    ruleTotal.value = Number(data?.total) || 0
    if (!rules.value.length && ruleTotal.value > 0 && rulePage.value > 1) {
      await loadRules(rulePage.value - 1)
    }
  } catch (error) {
    reportError(error, '统计规则加载失败')
  } finally {
    loadingRules.value = false
  }
}

function defaultDataSourceKey() {
  if (query.dataSourceKey) {
    return query.dataSourceKey
  }
  return dataSources.value.length === 1 ? dataSources.value[0].key : ''
}

function openCreate() {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可以新增统计规则')
    return
  }
  if (!dataSources.value.length) {
    ElMessage.warning('暂无可用数据源，请先联系管理员配置并授权')
    return
  }
  editingId.value = null
  readOnly.value = false
  ruleMeta.value = null
  resetForm({ dataSourceKey: defaultDataSourceKey() })
  dialogVisible.value = true
}

async function openDetail(row) {
  if (!row) {
    return
  }
  editingId.value = row.id
  readOnly.value = !(isAdmin.value && row.canEdit)
  resetForm({
    dataSourceKey: row.dataSourceKey || '',
    ruleCode: row.ruleCode || '',
    ruleName: row.ruleName || '',
    ruleDescription: row.ruleDescription || '',
    formula: row.formula || '',
    businessRule: row.businessRule || '',
    remark: row.remark || '',
    inputFields: []
  })
  ruleMeta.value = null
  dialogVisible.value = true
  dialogLoading.value = true
  try {
    const detail = await fetchRule(row.id)
    readOnly.value = !(isAdmin.value && detail?.canEdit)
    ruleMeta.value = detail || null
    resetForm({
      dataSourceKey: detail?.dataSourceKey || '',
      ruleCode: detail?.ruleCode || '',
      ruleName: detail?.ruleName || '',
      ruleDescription: detail?.ruleDescription || '',
      formula: detail?.formula || '',
      businessRule: detail?.businessRule || '',
      remark: detail?.remark || '',
      inputFields: (detail?.inputFields || []).map((field) => ({
        sourceTable: field.sourceTable || '',
        fieldName: field.fieldName || '',
        fieldDescription: field.fieldDescription || ''
      }))
    })
  } catch (error) {
    dialogVisible.value = false
    reportError(error, '规则详情加载失败')
  } finally {
    dialogLoading.value = false
  }
}

function addField() {
  if (form.inputFields.length >= 50) {
    return
  }
  form.inputFields.push({ sourceTable: '', fieldName: '', fieldDescription: '' })
}

function removeField(index) {
  form.inputFields.splice(index, 1)
}

function buildPayload() {
  return {
    dataSourceKey: form.dataSourceKey,
    ruleCode: form.ruleCode.trim(),
    ruleName: form.ruleName.trim(),
    ruleDescription: form.ruleDescription.trim(),
    formula: form.formula.trim(),
    businessRule: form.businessRule.trim(),
    remark: form.remark.trim(),
    inputFields: form.inputFields
      .map((field) => ({
        sourceTable: field.sourceTable.trim(),
        fieldName: field.fieldName.trim(),
        fieldDescription: field.fieldDescription.trim()
      }))
      .filter((field) => field.sourceTable || field.fieldName || field.fieldDescription)
  }
}

function validatePayload(payload) {
  if (!payload.dataSourceKey) {
    return '请选择规则所属数据源'
  }
  if (!payload.ruleCode) {
    return '请填写规则代码'
  }
  if (!payload.ruleName) {
    return '请填写规则名称'
  }
  const seen = new Set()
  for (const field of payload.inputFields) {
    if (!field.sourceTable || !field.fieldName) {
      return '输入字段的来源表和字段名不能为空'
    }
    const key = `${field.sourceTable}\u0000${field.fieldName}`.toLowerCase()
    if (seen.has(key)) {
      return `输入字段重复：${field.sourceTable}.${field.fieldName}`
    }
    seen.add(key)
  }
  return ''
}

async function saveRule() {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可以维护统计规则')
    return
  }
  if (readOnly.value || saving.value) {
    return
  }
  const payload = buildPayload()
  const problem = validatePayload(payload)
  if (problem) {
    ElMessage.warning(problem)
    return
  }

  saving.value = true
  try {
    if (editingId.value) {
      await updateRule(editingId.value, payload)
      ElMessage.success('统计规则已更新')
    } else {
      await createRule(payload)
      ElMessage.success('统计规则已创建')
    }
    dialogVisible.value = false
    pageError.value = ''
    emit('changed')
    await loadRules(editingId.value ? rulePage.value : 1)
  } catch (error) {
    reportError(error, '统计规则保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  if (!row || !isAdmin.value || !row.canEdit) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除统计规则「${row.ruleName}」？删除后该规则不会再出现在对话页。`,
      '删除统计规则',
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

  try {
    await deleteRule(row.id)
    ElMessage.success('统计规则已删除')
    pageError.value = ''
    emit('changed')
    const nextPage = rules.value.length === 1 && rulePage.value > 1
      ? rulePage.value - 1
      : rulePage.value
    await loadRules(nextPage)
  } catch (error) {
    reportError(error, '统计规则删除失败')
  }
}

onMounted(() => {
  loadDataSources()
  loadRules(1)
})

function handleSizeChange() {
  loadRules(1)
}
</script>

