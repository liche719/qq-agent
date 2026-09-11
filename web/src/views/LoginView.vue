<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'
import { auth, lastUsername, saveAuth } from '../auth'
import { theme, themeLabel, toggleTheme } from '../theme'

const DEFAULT_USERNAME = 'rootlcw'

const router = useRouter()
const username = ref(lastUsername() || DEFAULT_USERNAME)
const password = ref('')
const remember = ref(true)
const message = ref('')
const busy = ref(false)
const passwordInput = ref(null)

onMounted(() => {
  if (auth.expired) message.value = '登录状态已失效，请重新登录。'
  passwordInput.value?.focus()
})

async function submit() {
  const account = username.value.trim()
  if (!account || !password.value) {
    message.value = '请输入账号和密码。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    await login(account, password.value)
    saveAuth(account, password.value, remember.value)
    router.replace({ name: 'dashboard' })
  } catch (error) {
    message.value = error.message || '登录失败，请稍后再试。'
    password.value = ''
    passwordInput.value?.focus()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <form class="glass login-card" @submit.prevent="submit">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true"></div>
        <h1>运维监控</h1>
        <button class="btn btn-sm theme-toggle" type="button"
                :title="theme.mode === 'dark' ? '切回白色主题' : '切换到黑色主题'"
                @click="toggleTheme">
          {{ themeLabel() }}主题
        </button>
      </div>
      <p class="lead">登录后可查看 QQ 陪伴机器人的运行状态、任务与用户记忆。</p>

      <div class="field">
        <label for="username">账号</label>
        <input id="username" v-model="username" type="text" autocomplete="username"
               placeholder="请输入账号" required>
      </div>

      <div class="field">
        <label for="password">密码</label>
        <input id="password" ref="passwordInput" v-model="password" type="password"
               autocomplete="current-password" placeholder="请输入密码" required>
        <div class="error" role="alert">{{ message }}</div>
      </div>

      <div class="check">
        <input id="remember" v-model="remember" type="checkbox">
        <label for="remember">记住账号密码（下次自动登录，共用电脑请勿勾选）</label>
      </div>

      <button class="btn btn-primary submit" type="submit" :disabled="busy">
        {{ busy ? '正在登录…' : '登录' }}
      </button>

      <p class="login-foot">连续输错 5 次会暂时禁止访问（按来源 IP 计算，不影响其它设备）。</p>
    </form>
  </div>
</template>
