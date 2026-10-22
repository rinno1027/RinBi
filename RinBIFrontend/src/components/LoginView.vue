<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-mark login-mark">R</div>
        <div class="login-brand-text">
          <h1>RinBI 数据问答</h1>
          <p>登录后使用自己的数据源与历史会话</p>
        </div>
      </div>

      <div class="login-mode-switch">
        <button
          type="button"
          :class="{ active: authMode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          :class="{ active: authMode === 'register' }"
          @click="switchMode('register')"
        >
          申请注册
        </button>
      </div>

      <form class="login-form" @submit.prevent="handleSubmit">
        <label v-if="isRegister" class="login-field">
          <span>显示名称</span>
          <el-input
            v-model="displayName"
            size="large"
            maxlength="64"
            placeholder="选填，默认使用用户名"
            :disabled="loading"
          />
        </label>

        <label class="login-field">
          <span>用户名</span>
          <el-input
            ref="usernameRef"
            v-model="username"
            size="large"
            autocomplete="username"
            placeholder="3-32 位字母、数字或下划线"
            :disabled="loading"
          />
        </label>

        <label class="login-field">
          <span>密码</span>
          <el-input
            v-model="password"
            type="password"
            size="large"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            :placeholder="isRegister ? '至少 8 位字符' : '请输入密码'"
            show-password
            :disabled="loading"
            @keydown.enter="handleSubmit"
          />
        </label>

        <label v-if="isRegister" class="login-field">
          <span>确认密码</span>
          <el-input
            v-model="confirmPassword"
            type="password"
            size="large"
            autocomplete="new-password"
            placeholder="请再次输入密码"
            show-password
            :disabled="loading"
            @keydown.enter="handleSubmit"
          />
        </label>

        <el-alert
          v-if="error"
          class="login-error"
          type="error"
          :title="error"
          :closable="false"
          show-icon
        />

        <el-button
          type="primary"
          size="large"
          class="login-button"
          native-type="submit"
          :loading="loading"
          :disabled="!canSubmit"
        >
          {{ loading ? (isRegister ? '提交中…' : '登录中…') : (isRegister ? '提交注册申请' : '登 录') }}
        </el-button>
      </form>

      <p class="login-hint">
        {{ isRegister
          ? '注册申请提交后需管理员审批，审批通过后即可登录并使用分配的数据源。'
          : '首次部署默认账号 admin / admin123，登录后请及时修改密码。' }}
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login, register } from '../api/auth'

const emit = defineEmits(['authenticated'])

const authMode = ref('login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const displayName = ref('')
const loading = ref(false)
const error = ref('')
const usernameRef = ref(null)

const isRegister = computed(() => authMode.value === 'register')
const canSubmit = computed(() => {
  if (loading.value || !username.value.trim() || !password.value) {
    return false
  }
  if (!isRegister.value) {
    return true
  }
  return password.value.length >= 8 && password.value === confirmPassword.value
})

function switchMode(mode) {
  if (authMode.value === mode || loading.value) {
    return
  }
  authMode.value = mode
  password.value = ''
  confirmPassword.value = ''
  error.value = ''
  nextTick(() => {
    if (usernameRef.value && typeof usernameRef.value.focus === 'function') {
      usernameRef.value.focus()
    }
  })
}

async function handleSubmit() {
  if (!canSubmit.value) {
    if (isRegister.value && password.value.length < 8) {
      error.value = '注册密码至少需要 8 位字符'
    } else if (isRegister.value && password.value !== confirmPassword.value) {
      error.value = '两次输入的密码不一致'
    }
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (isRegister.value) {
      const result = await register(
        username.value.trim(),
        password.value,
        displayName.value.trim()
      )
      ElMessage.success(result.message || '注册申请已提交，请等待管理员审批')
      authMode.value = 'login'
      password.value = ''
      confirmPassword.value = ''
      displayName.value = ''
      error.value = ''
      return
    }
    const result = await login(username.value.trim(), password.value)
    password.value = ''
    ElMessage.success(`欢迎回来，${result.user?.displayName || result.user?.username || ''}`)
    emit('authenticated', result.user)
  } catch (e) {
    error.value = e.message || (isRegister.value ? '注册申请失败' : '登录失败，请检查用户名与密码')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  nextTick(() => {
    if (usernameRef.value && typeof usernameRef.value.focus === 'function') {
      usernameRef.value.focus()
    }
  })
})
</script>

