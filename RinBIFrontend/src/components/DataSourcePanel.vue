<template>
  <section class="admin-page">
    <div class="admin-toolbar">
      <span class="admin-toolbar-hint">数据源由管理员统一配置，并授权给需要使用的用户。</span>
      <div class="admin-toolbar-actions">
        <el-button size="small" :loading="loadingDataSources" @click="loadDataSources()">
          刷新数据
        </el-button>
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
          <el-input
            v-model="dsQuery.keyword"
            size="small"
            clearable
            class="admin-search"
            placeholder="搜索数据源名称或 key"
            @keyup.enter="loadDataSources(1)"
            @clear="loadDataSources(1)"
          />
          <el-radio-group v-model="dsQuery.enabled" size="small" @change="loadDataSources(1)">
            <el-radio-button label="all">全部</el-radio-button>
            <el-radio-button label="enabled">启用</el-radio-button>
            <el-radio-button label="disabled">停用</el-radio-button>
          </el-radio-group>
        </div>
        <div class="admin-filter-right">
          <span class="admin-total">共 {{ dataSourceTotal }} 个数据源</span>
          <el-button type="primary" size="small" @click="openCreate">新增数据源</el-button>
        </div>
      </div>

      <el-table
        v-loading="loadingDataSources"
        :data="dataSources"
        size="small"
        class="admin-table"
        empty-text="还没有配置数据源"
      >
        <el-table-column label="数据源" min-width="185">
          <template #default="{ row }">
            <div class="admin-source-name">{{ row.name }}</div>
            <div class="admin-source-key">{{ row.dbKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.dbType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接地址" min-width="220">
          <template #default="{ row }">
            <span class="admin-source-target">{{ sourceTarget(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="授权用户" width="92" align="center">
          <template #default="{ row }">
            <span class="admin-count">{{ row.assignedUserCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="118">
          <template #default="{ row }">
            <span class="admin-time">{{ formatDateTime(row.updatedAt) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="184" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openAccess(row)">
              授权用户
            </el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-pagination">
        <el-pagination
          v-model:current-page="dsPage"
          v-model:page-size="dsSize"
          small
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="dataSourceTotal"
          @current-change="loadDataSources"
          @size-change="handleDataSourceSizeChange"
        />
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="min(820px, 94vw)"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" class="admin-ds-form">
        <div class="admin-form-grid">
          <el-form-item label="数据库 key" required>
            <el-input
              v-model="form.dbKey"
              maxlength="64"
              placeholder="例如 sales，用户查询时使用的唯一标识"
            />
          </el-form-item>
          <el-form-item label="显示名称" required>
            <el-input v-model="form.name" maxlength="128" placeholder="例如 销售生产库" />
          </el-form-item>
          <el-form-item label="数据库类型" required>
            <el-select v-model="form.dbType" class="admin-full" @change="handleDbTypeChange">
              <el-option label="MySQL / MariaDB" value="mysql" />
              <el-option label="PostgreSQL" value="postgresql" />
              <el-option label="H2" value="h2" />
              <el-option label="其他（需填写 JDBC URL 与驱动）" value="custom" />
            </el-select>
          </el-form-item>
          <el-form-item label="SQL 方言">
            <el-input v-model="form.dialect" maxlength="32" placeholder="默认与数据库类型一致" />
          </el-form-item>
          <el-form-item label="说明" class="admin-span-2">
            <el-input
              v-model="form.description"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 3 }"
              maxlength="512"
              show-word-limit
              placeholder="告诉用户这个库包含哪些业务数据"
            />
          </el-form-item>
        </div>

        <el-divider content-position="left">连接参数</el-divider>

        <div class="admin-form-grid">
          <el-form-item label="JDBC URL" class="admin-span-2">
            <el-input
              v-model="form.jdbcUrl"
              maxlength="1024"
              placeholder="可选。填写后优先于 host / port / 数据库名，例如 jdbc:mysql://db:3306/sales"
            />
          </el-form-item>
          <el-form-item label="登录地址">
            <el-input v-model="form.host" maxlength="255" placeholder="例如 10.0.0.12" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number
              v-model="form.port"
              class="admin-full"
              :min="1"
              :max="65535"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="数据库名">
            <el-input v-model="form.databaseName" maxlength="128" placeholder="例如 sales" />
          </el-form-item>
          <el-form-item label="数据库账号" required>
            <el-input v-model="form.username" maxlength="128" placeholder="只读查询账号" />
          </el-form-item>
          <el-form-item label="数据库密码">
            <el-input
              v-model="form.password"
              type="password"
              maxlength="128"
              show-password
              autocomplete="new-password"
              :placeholder="editingId ? '留空表示保留原密码' : 'AES-GCM 加密后保存'"
            />
          </el-form-item>
          <el-form-item label="驱动类名">
            <el-input
              v-model="form.driverClassName"
              maxlength="128"
              placeholder="留空按类型推断，例如 com.mysql.cj.jdbc.Driver"
            />
          </el-form-item>
          <el-form-item label="附加连接参数" class="admin-span-2">
            <el-input
              v-model="form.jdbcParams"
              maxlength="512"
              placeholder="例如 useUnicode=true&amp;characterEncoding=utf8&amp;useSSL=false&amp;serverTimezone=Asia/Shanghai"
            />
          </el-form-item>
        </div>

        <el-divider content-position="left">运行与连接池</el-divider>

        <div class="admin-form-grid">
          <el-form-item label="启用数据源">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="最大连接数">
            <el-input-number
              v-model="form.maxPoolSize"
              class="admin-full"
              :min="1"
              :max="20"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="连接超时（毫秒）">
            <el-input-number
              v-model="form.connectionTimeoutMillis"
              class="admin-full"
              :min="1000"
              :max="120000"
              :step="1000"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="初始化脚本（可选）" class="admin-span-2">
            <el-input
              v-model="form.initScript"
              maxlength="255"
              placeholder="classpath 相对路径，例如 db/demo.sql"
            />
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDataSource">
          {{ editingId ? '保存修改' : '创建数据源' }}
        </el-button>
      </template>
    </el-dialog>


    <el-dialog
      v-model="accessVisible"
      :title="accessTitle"
      width="min(620px, 94vw)"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="admin-access-toolbar">
        <el-input
          v-model="accessKeyword"
          size="small"
          clearable
          class="admin-search"
          placeholder="搜索用户"
        />
        <el-checkbox
          :model-value="allFilteredSelected"
          :indeterminate="someFilteredSelected"
          @change="toggleFilteredUsers"
        >
          全选当前列表
        </el-checkbox>
        <span class="admin-total">已选 {{ accessUserIds.length }} 人</span>
      </div>

      <div v-loading="loadingAccess" class="admin-access-list">
        <el-checkbox-group v-model="accessUserIds" class="admin-access-group">
          <el-checkbox
            v-for="user in filteredAccessUsers"
            :key="user.id"
            :label="user.id"
            class="admin-access-item"
          >
            <span class="admin-access-user">
              <span class="admin-access-name">{{ user.displayName || user.username }}</span>
              <span class="admin-access-meta">@{{ user.username }}</span>
              <el-tag size="small" effect="plain" :type="statusMeta(user).type">
                {{ statusMeta(user).label }}
              </el-tag>
            </span>
          </el-checkbox>
        </el-checkbox-group>
        <el-empty
          v-if="!loadingAccess && !filteredAccessUsers.length"
          description="没有符合条件的用户"
          :image-size="60"
        />
      </div>

      <template #footer>
        <el-button :disabled="savingAccess" @click="accessVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingAccess" @click="saveAccess">
          保存授权
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>


<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  assignAdminDataSourceUsers,
  createAdminDataSource,
  deleteAdminDataSource,
  fetchAdminDataSourceUsers,
  fetchAdminDataSources,
  updateAdminDataSource
} from '../api/admin'
import { formatDateTime } from '../utils/format'

defineEmits(['close'])

const dataSources = ref([])
const dataSourceTotal = ref(0)
const dsPage = ref(1)
const dsSize = ref(10)
const dsQuery = reactive({ keyword: '', enabled: 'all' })
const loadingDataSources = ref(false)

const pageError = ref('')
const dialogVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)

const accessVisible = ref(false)
const accessSource = ref(null)
const accessUsers = ref([])
const accessUserIds = ref([])
const accessKeyword = ref('')
const loadingAccess = ref(false)
const savingAccess = ref(false)

const form = reactive(emptyForm())

const selectedSource = computed(() => accessSource.value)
const dialogTitle = computed(() => (editingId.value ? '编辑数据源' : '新增数据源'))
const accessTitle = computed(() =>
  selectedSource.value ? `授权用户 · ${selectedSource.value.name}` : '授权用户'
)
const filteredAccessUsers = computed(() => {
  const term = accessKeyword.value.trim().toLowerCase()
  if (!term) {
    return accessUsers.value
  }
  return accessUsers.value.filter((user) => {
    const haystack = `${user.username || ''} ${user.displayName || ''}`.toLowerCase()
    return haystack.includes(term)
  })
})
const allFilteredSelected = computed(
  () =>
    filteredAccessUsers.value.length > 0 &&
    filteredAccessUsers.value.every((user) => accessUserIds.value.includes(user.id))
)
const someFilteredSelected = computed(
  () =>
    !allFilteredSelected.value &&
    filteredAccessUsers.value.some((user) => accessUserIds.value.includes(user.id))
)

function emptyForm() {
  return {
    dbKey: '',
    name: '',
    description: '',
    dbType: 'mysql',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    driverClassName: '',
    jdbcUrl: '',
    jdbcParams: '',
    dialect: 'mysql',
    enabled: true,
    maxPoolSize: 3,
    connectionTimeoutMillis: 10000,
    initScript: ''
  }
}

function resetForm(values) {
  Object.assign(form, emptyForm(), values || {})
}

function statusKey(status) {
  if (Number(status) === 2) {
    return 'pending'
  }
  if (Number(status) === 1) {
    return 'active'
  }
  return 'disabled'
}

function statusMeta(user) {
  const key = statusKey(user?.status)
  if (key === 'pending') {
    return { label: '待审批', type: 'warning' }
  }
  if (key === 'active') {
    return { label: '正常', type: 'success' }
  }
  return { label: '已禁用', type: 'info' }
}

function sourceTarget(row) {
  if (row?.jdbcUrl) {
    return row.jdbcUrl
  }
  if (row?.dbType === 'h2') {
    return row.databaseName ? `内存库 ${row.databaseName}` : '—'
  }
  const host = row?.host || ''
  const port = row?.port ? `:${row.port}` : ''
  const database = row?.databaseName ? `/${row.databaseName}` : ''
  return host ? `${host}${port}${database}` : database || '—'
}

function reportError(error, fallback) {
  if (error?.status === 401) {
    return
  }
  const message = error?.message || fallback
  pageError.value = message
  ElMessage.error(message)
}


async function loadDataSources(page = dsPage.value) {
  dsPage.value = Math.max(1, page)
  loadingDataSources.value = true
  try {
    const data = await fetchAdminDataSources({
      page: dsPage.value,
      size: dsSize.value,
      keyword: dsQuery.keyword.trim(),
      enabled: dsQuery.enabled
    })
    dataSources.value = Array.isArray(data?.items) ? data.items : []
    dataSourceTotal.value = Number(data?.total) || 0
    if (!dataSources.value.length && dataSourceTotal.value > 0 && dsPage.value > 1) {
      await loadDataSources(dsPage.value - 1)
    }
  } catch (error) {
    reportError(error, '数据源列表加载失败')
  } finally {
    loadingDataSources.value = false
  }
}

function handleDataSourceSizeChange() {
  loadDataSources(1)
}

async function openAccess(row) {
  if (!row) {
    return
  }
  accessSource.value = row
  accessVisible.value = true
  accessKeyword.value = ''
  accessUsers.value = []
  accessUserIds.value = []
  loadingAccess.value = true
  try {
    const data = await fetchAdminDataSourceUsers(row.id)
    accessUsers.value = Array.isArray(data?.users) ? data.users : []
    accessUserIds.value = Array.isArray(data?.assignedUserIds)
      ? [...data.assignedUserIds]
      : []
  } catch (error) {
    reportError(error, '授权用户加载失败')
    accessVisible.value = false
  } finally {
    loadingAccess.value = false
  }
}

function toggleFilteredUsers(checked) {
  const visibleIds = filteredAccessUsers.value.map((user) => user.id)
  if (checked) {
    accessUserIds.value = Array.from(new Set([...accessUserIds.value, ...visibleIds]))
  } else {
    const hidden = new Set(visibleIds)
    accessUserIds.value = accessUserIds.value.filter((id) => !hidden.has(id))
  }
}

async function saveAccess() {
  if (!accessSource.value || savingAccess.value) {
    return
  }
  savingAccess.value = true
  try {
    await assignAdminDataSourceUsers(accessSource.value.id, accessUserIds.value)
    ElMessage.success('授权已保存')
    accessVisible.value = false
    await loadDataSources()
  } catch (error) {
    reportError(error, '授权保存失败')
  } finally {
    savingAccess.value = false
  }
}

function handleDbTypeChange(value) {
  const defaultPorts = { mysql: 3306, postgresql: 5432 }
  if (value !== 'custom') {
    form.dialect = value
  }
  if (defaultPorts[value]) {
    form.port = defaultPorts[value]
  }
  if (value === 'h2') {
    form.host = ''
    form.port = 3306
  }
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  if (!row) {
    return
  }
  editingId.value = row.id
  resetForm({
    dbKey: row.dbKey || '',
    name: row.name || '',
    description: row.description || '',
    dbType: row.dbType || 'mysql',
    host: row.host || '',
    port: row.port || 3306,
    databaseName: row.databaseName || '',
    username: row.username || '',
    password: '',
    driverClassName: row.driverClassName || '',
    jdbcUrl: row.jdbcUrl || '',
    jdbcParams: row.jdbcParams || '',
    dialect: row.dialect || row.dbType || 'mysql',
    enabled: row.enabled !== false,
    maxPoolSize: row.maxPoolSize || 3,
    connectionTimeoutMillis: Number(row.connectionTimeoutMillis) || 10000,
    initScript: row.initScript || ''
  })
  dialogVisible.value = true
}

function validateForm() {
  const key = form.dbKey.trim()
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(key)) {
    ElMessage.warning('数据库 key 仅支持 1-64 位字母、数字、下划线和短横线')
    return false
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写数据源显示名称')
    return false
  }
  if (!form.username.trim()) {
    ElMessage.warning('请填写数据库账号')
    return false
  }
  const hasJdbcUrl = Boolean(form.jdbcUrl.trim())
  if (!hasJdbcUrl && !form.databaseName.trim()) {
    ElMessage.warning('未填写 JDBC URL 时必须填写数据库名')
    return false
  }
  if (!hasJdbcUrl && ['mysql', 'postgresql'].includes(form.dbType) && !form.host.trim()) {
    ElMessage.warning('MySQL / PostgreSQL 数据源必须填写登录地址')
    return false
  }
  if (form.dbType === 'custom' && !hasJdbcUrl) {
    ElMessage.warning('其他类型数据源必须填写 JDBC URL')
    return false
  }
  return true
}

function buildPayload() {
  return {
    dbKey: form.dbKey.trim(),
    name: form.name.trim(),
    description: form.description.trim(),
    dbType: form.dbType,
    host: form.host.trim(),
    port: form.port,
    databaseName: form.databaseName.trim(),
    username: form.username.trim(),
    password: form.password || (editingId.value ? null : ''),
    driverClassName: form.driverClassName.trim(),
    jdbcUrl: form.jdbcUrl.trim(),
    jdbcParams: form.jdbcParams.trim(),
    dialect: form.dialect.trim() || form.dbType,
    enabled: form.enabled,
    maxPoolSize: form.maxPoolSize,
    connectionTimeoutMillis: form.connectionTimeoutMillis,
    initScript: form.initScript.trim()
  }
}

async function saveDataSource() {
  if (!validateForm() || saving.value) {
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateAdminDataSource(editingId.value, buildPayload())
      ElMessage.success('数据源配置已更新')
    } else {
      await createAdminDataSource(buildPayload())
      ElMessage.success('数据源已创建')
    }
    dialogVisible.value = false
    pageError.value = ''
    await loadDataSources()
  } catch (error) {
    reportError(error, '数据源保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  if (!row) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除数据源「${row.name}」？删除后所有已授权用户将立即失去访问权限。`,
      '删除数据源',
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
    await deleteAdminDataSource(row.id)
    ElMessage.success('数据源已删除')
    await loadDataSources()
  } catch (error) {
    reportError(error, '数据源删除失败')
  }
}

onMounted(() => {
  loadDataSources()
})
</script>

