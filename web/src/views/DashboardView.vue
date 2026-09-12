<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { clearAuth } from '../auth'
import { fmtNum, toneOf, zh } from '../labels'
import { theme, themeLabel, toggleTheme } from '../theme'
import StatCard from '../components/StatCard.vue'
import StatusPill from '../components/StatusPill.vue'
import OverviewPanel from '../panels/OverviewPanel.vue'
import QqPanel from '../panels/QqPanel.vue'
import LlmPanel from '../panels/LlmPanel.vue'
import MaimemoPanel from '../panels/MaimemoPanel.vue'
import TasksPanel from '../panels/TasksPanel.vue'
import ScheduledPanel from '../panels/ScheduledPanel.vue'
import UsersPanel from '../panels/UsersPanel.vue'
import LogsPanel from '../panels/LogsPanel.vue'

const TABS = [
  { key: 'overview', label: '总览' },
  { key: 'qq', label: 'QQ 通道' },
  { key: 'llm', label: '模型与搜索' },
  { key: 'maimemo', label: '背单词' },
  { key: 'tasks', label: '任务' },
  { key: 'scheduled', label: '定时任务' },
  { key: 'users', label: '用户与记忆' },
  { key: 'logs', label: '日志' }
]
const INTERVALS = [5000, 10000, 30000, 60000, 0]

const router = useRouter()
const tab = ref(readSetting('admin.tab', 'overview'))
const interval = ref(Number(readSetting('admin.interval', '10000')))
const overview = ref(null)
const history = ref([])
const tick = ref(0)
const busy = ref(false)
const stopped = ref(false)
const lastSuccess = ref('')
const errorText = ref(null)
const tickWidth = ref(0)

let refreshTimer = null
let progressTimer = null
let tickStart = Date.now()

function readSetting(key, fallback) {
  try {
    return localStorage.getItem(key) ?? fallback
  } catch (ignored) {
    return fallback
  }
}

function writeSetting(key, value) {
  try {
    localStorage.setItem(key, String(value))
  } catch (ignored) {
    /* 忽略 */
  }
}

const status = computed(() => overview.value?.status || '')
const qqStatus = computed(() => overview.value?.qq || '')
const memoryCount = computed(() => ['coreMemories', 'workMemories', 'episodes']
  .map(key => Number(overview.value?.[key]) || 0)
  .reduce((left, right) => left + right, 0))
const unknownTasks = computed(() => Number(overview.value?.tasks?.UNKNOWN_RESULT) || 0)

const statusLine = computed(() => {
  if (errorText.value) {
    return '刷新失败：' + errorText.value + (lastSuccess.value ? ' · 最后成功刷新：' + lastSuccess.value : '')
  }
  if (!lastSuccess.value) return '正在加载…'
  return '最后成功刷新：' + lastSuccess.value
    + (interval.value > 0 ? ' · 每 ' + interval.value / 1000 + ' 秒自动刷新' : ' · 已关闭自动刷新')
})

async function refresh(force = false) {
  if (busy.value) return
  if (stopped.value && !force) return
  busy.value = true
  try {
    overview.value = await api('/overview')
    // tick 一成功就推进：各页签靠它重载自己的数据，不能因为趋势图接口失败就整体不刷新
    tick.value += 1
    try {
      history.value = await api('/metrics/history?limit=90')
    } catch (ignored) {
      /* 趋势图失败不影响其它页签刷新 */
    }
    errorText.value = null
    stopped.value = false
    lastSuccess.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (error.status === 401) {
      clearAuth(true)
      router.replace({ name: 'login' })
      return
    }
    if (error.status === 429) stopAuto()
    errorText.value = error.message
  } finally {
    busy.value = false
    tickStart = Date.now()
    tickWidth.value = 0
  }
}

function schedule() {
  clearInterval(refreshTimer)
  clearInterval(progressTimer)
  refreshTimer = null
  progressTimer = null
  if (interval.value <= 0) {
    tickWidth.value = 0
    return
  }
  tickStart = Date.now()
  refreshTimer = setInterval(() => {
    if (!stopped.value) refresh()
  }, interval.value)
  progressTimer = setInterval(() => {
    tickWidth.value = Math.min(100, (Date.now() - tickStart) / interval.value * 100)
  }, 1000)
}

function stopAuto() {
  stopped.value = true
  interval.value = 0
  writeSetting('admin.interval', 0)
  schedule()
}

function logout() {
  clearAuth(false)
  router.replace({ name: 'login' })
}

watch(tab, value => writeSetting('admin.tab', value))

watch(interval, value => {
  stopped.value = false
  writeSetting('admin.interval', value)
  schedule()
})

onMounted(() => {
  schedule()
  refresh(true)
})

onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  clearInterval(progressTimer)
})
</script>

<template>
  <div class="page">
    <header class="glass topbar">
      <div class="spread">
        <div class="brand">
          <div class="brand-mark" aria-hidden="true"></div>
          <div class="brand-text">
            <h1>运维监控</h1>
            <div class="sub">QQ 陪伴机器人后台</div>
          </div>
        </div>
        <div class="row">
          <StatusPill :label="'应用 ' + zh('app', status)" :tone="toneOf(status)"></StatusPill>
          <StatusPill :label="'QQ 通道 ' + zh('qq', qqStatus)" :tone="toneOf(qqStatus)"></StatusPill>
        </div>
      </div>

      <div class="spread" style="margin-top: 13px">
        <div class="tools" style="margin-left: 0">
          <select v-model.number="interval" aria-label="刷新频率">
            <option v-for="value in INTERVALS" :key="value" :value="value">
              {{ value === 0 ? '不自动刷新' : '每 ' + value / 1000 + ' 秒刷新' }}
            </option>
          </select>
          <button class="btn btn-primary" :disabled="busy" @click="refresh(true)">
            {{ busy ? '刷新中…' : '立即刷新' }}
          </button>
          <button class="btn theme-toggle" :title="theme.mode === 'dark' ? '切回白色主题' : '切换到黑色主题'"
                  @click="toggleTheme">
            {{ themeLabel() }}主题
          </button>
          <button class="btn" @click="logout">退出登录</button>
        </div>
      </div>

      <div class="tick" aria-hidden="true"><i :style="{ width: tickWidth + '%' }"></i></div>
    </header>

    <div v-if="overview?.alerts?.length" class="stack" style="margin-top: 16px">
      <div v-for="alert in overview.alerts" :key="alert" class="alert">{{ alert }}</div>
    </div>

    <div class="grid-4" style="margin-top: 16px">
      <StatCard label="应用状态" :value="zh('app', status)"
                :meta="'启动于 ' + (overview?.startedAt || '—')" :tone="toneOf(status)"></StatCard>
      <StatCard label="QQ 通道" :value="zh('qq', qqStatus)"
                :meta="'发送成功 ' + fmtNum(overview?.qqMetrics?.textSendSuccess) + ' · 失败 ' + fmtNum(overview?.qqMetrics?.textSendFailure)"
                :tone="toneOf(qqStatus)"></StatCard>
      <StatCard label="用户数" :value="fmtNum(overview?.users)"
                :meta="'对话 ' + fmtNum(overview?.conversations) + ' · 记忆 ' + fmtNum(memoryCount)"></StatCard>
      <StatCard label="异常任务" :value="fmtNum(unknownTasks)"
                :meta="'运行中 ' + fmtNum(overview?.tasks?.RUNNING) + ' · 失败 ' + fmtNum(overview?.tasks?.FAILED)"
                :tone="unknownTasks > 0 ? 'bad' : 'ok'"></StatCard>
    </div>

    <nav class="glass tabs" role="tablist">
      <button v-for="item in TABS" :key="item.key" class="tab" :class="{ active: tab === item.key }"
              role="tab" @click="tab = item.key">{{ item.label }}</button>
    </nav>

    <OverviewPanel v-if="tab === 'overview'" :overview="overview" :history="history"></OverviewPanel>
    <QqPanel v-else-if="tab === 'qq'" :overview="overview" @refresh="refresh(true)"></QqPanel>
    <LlmPanel v-else-if="tab === 'llm'" :tick="tick"></LlmPanel>
    <MaimemoPanel v-else-if="tab === 'maimemo'" :tick="tick"></MaimemoPanel>
    <TasksPanel v-else-if="tab === 'tasks'" :tick="tick"></TasksPanel>
    <ScheduledPanel v-else-if="tab === 'scheduled'" :tick="tick"></ScheduledPanel>
    <UsersPanel v-else-if="tab === 'users'" :tick="tick"></UsersPanel>
    <LogsPanel v-else :tick="tick"></LogsPanel>

    <div class="status-line" :class="{ stale: Boolean(errorText) }" role="status">{{ statusLine }}</div>
  </div>
</template>
