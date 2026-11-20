<template>
  <section class="admin-page">
    <div class="admin-toolbar">
      <span class="admin-toolbar-hint">待审批账号优先显示，通过审批后用户即可登录并访问已授权的数据源。</span>
      <div class="admin-toolbar-actions">
        <el-button size="small" :loading="loadingUsers" @click="loadUsers()">
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
            v-model="userQuery.keyword"
            size="small"
            clearable
            class="admin-search"
            placeholder="搜索用户名或显示名称"
            @keyup.enter="loadUsers(1)"
            @clear="loadUsers(1)"
          />
          <el-radio-group v-model="userQuery.status" size="small" @change="loadUsers(1)">
            <el-radio-button label="all">全部</el-radio-button>
            <el-radio-button label="pending">待审批</el-radio-button>
            <el-radio-button label="active">正常</el-radio-button>
            <el-radio-button label="disabled">禁用</el-radio-button>
          </el-radio-group>
        </div>
        <span class="admin-total">共 {{ userTotal }} 个账号</span>
      </div>

      <el-table v-loading="loadingUsers" :data="users" size="small" class="admin-table">
        <el-table-column label="用户" min-width="185">
          <template #default="{ row }">
            <div class="admin-user-cell">
              <span class="admin-user-avatar">{{ userInitial(row) }}</span>
              <span class="admin-user-main">
                <span class="admin-user-name">{{ row.displayName || row.username }}</span>
                <span class="admin-user-handle">@{{ row.username }} · ID {{ row.id }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusMeta(row).type">
              {{ statusMeta(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="82">
          <template #default="{ row }">
            <span class="admin-role">{{ row.role }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数据源" width="82" align="center">
          <template #default="{ row }">
            <span class="admin-count">{{ row.dataSourceCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="118">
          <template #default="{ row }">
            <span class="admin-time">{{ formatDateTime(row.createdAt) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="166" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 1"
              link
              type="primary"
              size="small"
              :loading="actionUserId === row.id"
              @click="handleApprove(row)"
            >
              {{ row.status === 2 ? '通过申请' : '启用账号' }}
            </el-button>
            <el-button
              v-if="row.status !== 0 && row.id !== currentUser?.id"
              link
              type="danger"
              size="small"
              :loading="actionUserId === row.id"
              @click="handleDisable(row)"
            >
              {{ row.status === 2 ? '拒绝申请' : '禁用账号' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-pagination">
        <el-pagination
          v-model:current-page="userPage"
          v-model:page-size="userSize"
          small
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="userTotal"
          @current-change="loadUsers"
          @size-change="handleUserSizeChange"
        />
      </div>
    </section>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approveAdminUser, disableAdminUser, fetchAdminUsers } from '../api/admin'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  currentUser: {
    type: Object,
    default: null
  }
})

defineEmits(['close'])

const users = ref([])
const userTotal = ref(0)
const userPage = ref(1)
const userSize = ref(10)
const userQuery = reactive({ keyword: '', status: 'all' })
const loadingUsers = ref(false)
const actionUserId = ref(null)

const pageError = ref('')

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

function userInitial(user) {
  const name = user?.displayName || user?.username || '?'
  return String(name).trim().charAt(0).toUpperCase()
}

function reportError(error, fallback) {
  if (error?.status === 401) {
    return
  }
  const message = error?.message || fallback
  pageError.value = message
  ElMessage.error(message)
}

async function loadUsers(page = userPage.value) {
  userPage.value = Math.max(1, page)
  loadingUsers.value = true
  try {
    const data = await fetchAdminUsers({
      page: userPage.value,
      size: userSize.value,
      keyword: userQuery.keyword.trim(),
      status: userQuery.status
    })
    users.value = Array.isArray(data?.items) ? data.items : []
    userTotal.value = Number(data?.total) || 0
    if (!users.value.length && userTotal.value > 0 && userPage.value > 1) {
      await loadUsers(userPage.value - 1)
    }
  } catch (error) {
    reportError(error, '用户列表加载失败')
  } finally {
    loadingUsers.value = false
  }
}

function handleUserSizeChange() {
  loadUsers(1)
}

async function handleApprove(user) {
  if (!user || actionUserId.value) {
    return
  }
  const pending = user.status === 2
  try {
    await ElMessageBox.confirm(
      pending
        ? `确认通过 ${user.displayName || user.username} 的注册申请？通过后该账号即可登录。`
        : `确认启用账号 ${user.displayName || user.username}？`,
      pending ? '审批注册申请' : '启用账号',
      {
        type: 'info',
        confirmButtonText: pending ? '通过申请' : '启用',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )
  } catch (error) {
    return
  }

  actionUserId.value = user.id
  try {
    await approveAdminUser(user.id)
    ElMessage.success(pending ? '已通过注册申请' : '账号已启用')
    await loadUsers()
  } catch (error) {
    reportError(error, '账号状态更新失败')
  } finally {
    actionUserId.value = null
  }
}

async function handleDisable(user) {
  if (!user || actionUserId.value || user.id === props.currentUser?.id) {
    return
  }
  const pending = user.status === 2
  try {
    await ElMessageBox.confirm(
      pending
        ? `确认拒绝 ${user.displayName || user.username} 的注册申请？拒绝后账号将无法登录。`
        : `确认禁用账号 ${user.displayName || user.username}？该用户的在线会话会立即失效。`,
      pending ? '拒绝注册申请' : '禁用账号',
      {
        type: 'warning',
        confirmButtonText: pending ? '拒绝申请' : '禁用',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )
  } catch (error) {
    return
  }

  actionUserId.value = user.id
  try {
    await disableAdminUser(user.id)
    ElMessage.success(pending ? '已拒绝注册申请' : '账号已禁用')
    await loadUsers()
  } catch (error) {
    reportError(error, '账号状态更新失败')
  } finally {
    actionUserId.value = null
  }
}

onMounted(() => {
  loadUsers()
})
</script>

