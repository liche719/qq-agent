<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { clearAuth } from '../auth'
import { fmtNum, toneOf, zh } from '../labels'
import { theme, themeLabel, toggleTheme } from '../theme'
import StatusPill from '../components/StatusPill.vue'
import DescriptorPanel from '../components/DescriptorPanel.vue'
import { CORE_PANELS, CORE_TABS, CORE_TAB_LABELS } from '../panels/registry'

const INTERVALS = [5000, 10000, 30000, 60000, 0]
/** 页签 key 的形状（清单里的 key 都是这种短标识），用于粗筛被改坏的 localStorage */
const TAB_KEY = /^[A-Za-z0-9_-]{1,40}$/

const router = useRouter()
const tab = ref(readTab())
/** 页签清单：先按内置 8 个渲染，/api/admin/panels 回来后再替换（后端新增模块不用改前端） */
const tabs = ref(CORE_TABS)

/**
 * LLM 账户余额（2026-10-20 加）。
 * 单独一个接口，因为它是 DeepSeek 的外部查询（后端缓存 5 分钟）。
 * 查不到时显示"—"而不是 0——"查不到"和"没钱了"是两回事。
 */
/** 顶栏的「更多」开关：小屏默认收起刷新频率/主题/退出，先把地方让给内容 */
const moreOpen = ref(false)

/**
 * 清掉"结果未知"的任务状态（2026-10-21 加）。
 * 这类任务是发消息后没等到平台确认留下的，永远卡着会让面板一直显示"降级"。
 * 点一下就好——只删状态记录，不动对话内容。
 */
const dismissBusy = ref(false)
const dismissNotice = ref('')
async function dismissUnknownTasks() {
  dismissBusy.value = true
  dismissNotice.value = ''
  try {
    dismissNotice.value = (await api('/actions/dismiss-unknown-tasks', { method: 'POST' }))?.message || '已清理'
    await refresh(true)
  } catch (error) {
    dismissNotice.value = '清理失败：' + (error?.message || error)
  } finally {
    dismissBusy.value = false
  }
}
const balance = ref(null)
const balanceError = ref('')

async function loadBalance() {
  try {
    balance.value = await api('/balance')
    balanceError.value = ''
  } catch (e) {
    balanceError.value = e?.message || '查不到'
  }
}

/** 余额显示文案：< 5 元标红提醒 */
const balanceText = computed(() => {
  const raw = balance.value?.balance
  if (raw === undefined || raw === null || raw === '') return '—'
  const n = Number(raw)
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : String(raw)
})
const balanceTone = computed(() => {
  if (balanceError.value || balance.value?.error) return 'idle'
  const n = Number(balance.value?.balance)
  if (!Number.isFinite(n)) return 'idle'
  if (n < 3) return 'bad'
  if (n < 8) return 'warn'
  return 'ok'
})
const panelsError = ref('')
const interval = ref(readInterval())
const overview = ref(null)
const history = ref([])
const tick = ref(0)
const busy = ref(false)
const stopped = ref(false)
const lastSuccess = ref('')
const errorText = ref(null)
const tickWidth = ref(0)
/** 页签清单还在路上（此时可能还没法判断当前页签合不合法） */
const panelsLoading = ref(true)

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

/** 刷新间隔必须是下拉框里给出的那几个值，localStorage 被改坏时不能让它变成 NaN（会变成每毫秒一次的忙循环） */
function readInterval() {
  const value = Number(readSetting('admin.interval', '10000'))
  return INTERVALS.includes(value) ? value : 10000
}

/** 页签必须是清单里的 key，非法值会让所有页签都不匹配、只剩空壳。
 *  但清单要等 /panels 回来才有，所以这里只做形状粗筛（上次停在描述式页签时它的 key 不在内置注册表里），
 *  拿到清单后再用清单校正（见 loadPanels）。 */
function readTab() {
  const value = readSetting('admin.tab', 'overview')
  return TAB_KEY.test(value) ? value : 'overview'
}

/** 拉后端页签清单；接口还没上线/失败时退回内置 8 个页签，页签栏照常能用，错误只提示在内容区 */
async function loadPanels() {
  try {
    const result = await api('/panels')
    const list = Array.isArray(result?.tabs) ? result.tabs.filter(item => item && item.key) : []
    if (!list.length) throw new Error('清单为空')
    tabs.value = list.map(item => ({
      ...item,
      label: item.label || CORE_TAB_LABELS[item.key] || item.key,
      kind: item.kind || (CORE_PANELS[item.key] ? 'core' : 'descriptor')
    }))
    panelsError.value = ''
  } catch (error) {
    tabs.value = CORE_TABS
    panelsError.value = error.message
  } finally {
    panelsLoading.value = false
  }
  // 上次停的页签如果不在清单里（比如后端下线了某个模块），回落到总览
  if (!tabs.value.some(item => item.key === tab.value)) tab.value = 'overview'
}

const status = computed(() => overview.value?.status || '')
const qqStatus = computed(() => overview.value?.qq || '')
// 2026-09-18：记忆三张表合并成一张，总览按 kind 给计数（长期设定 / 中期事项 / 经历）
const memoryCount = computed(() => {
  const kinds = overview.value?.memoryKinds || {}
  return ['PROFILE', 'TASK', 'EXPERIENCE']
    .map(key => Number(kinds[key]) || 0)
    .reduce((left, right) => left + right, 0)
})
const unknownTasks = computed(() => Number(overview.value?.tasks?.UNKNOWN_RESULT) || 0)

const activeTab = computed(() => tabs.value.find(item => item.key === tab.value) || null)
/** kind=core：用注册表里的手写组件；kind=descriptor：用通用描述式面板 */
const activeCore = computed(() => (activeTab.value?.kind === 'core' ? CORE_PANELS[activeTab.value.key] || null : null))
const activeDescriptor = computed(() => (activeTab.value?.kind === 'descriptor' ? activeTab.value : null))

/** 现有 8 个页签的 props 语义保持不变：总览拿 overview/history，QQ 通道拿 overview，其余拿 tick */
const coreProps = computed(() => {
  if (activeTab.value?.key === 'overview') return { overview: overview.value, history: history.value }
  if (activeTab.value?.key === 'qq') return { overview: overview.value }
  return { tick: tick.value }
})

/** 原来只有「QQ 通道」接了 @refresh（改配置后顺手刷新总览），行为照旧 */
const coreEvents = computed(() => (activeTab.value?.key === 'qq' ? { refresh: () => refresh(true) } : {}))

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
    // 趋势图只有「总览」页签在用，其他页签不请求；失败也不清空已有数据，避免切回总览时闪一下空白
    if (tab.value === 'overview') {
      try {
        history.value = await api('/metrics/history?limit=60')
      } catch (ignored) {
        /* 趋势图失败不影响其它页签刷新 */
      }
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
  loadPanels()
  loadBalance()
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
        <div class="row">
          <!-- 余额放最显眼的位置：一眼看到还有多少 -->
          <button class="balance-pill" :class="'tone-' + balanceTone" :title="balanceError || '点一下重新查'"
                  @click="loadBalance">
            <span class="balance-label">余额</span>
            <span class="balance-value">{{ balanceText }}</span>
          </button>
          <StatusPill :label="'应用 ' + zh('app', status)" :tone="toneOf(status)"></StatusPill>
          <StatusPill :label="'QQ 通道 ' + zh('qq', qqStatus)" :tone="toneOf(qqStatus)"></StatusPill>
          <button class="btn topbar-more-toggle" :class="{ active: moreOpen }"
                  :aria-expanded="moreOpen" title="刷新频率 / 主题 / 退出"
                  @click="moreOpen = !moreOpen">{{ moreOpen ? '收起' : '⋯' }}</button>
        </div>
      </div>

      <div class="spread topbar-more" :class="{ open: moreOpen }" style="margin-top: 13px">
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
      <div v-for="alert in overview.alerts" :key="alert" class="alert alert-actionable">
        <span class="alert-text">{{ alert }}</span>
        <button v-if="String(alert).includes('没等到平台确认')" class="btn btn-sm"
                :disabled="dismissBusy" @click="dismissUnknownTasks">
          {{ dismissBusy ? '清理中…' : '清掉' }}
        </button>
      </div>
    </div>

    <p v-if="dismissNotice" class="hint" style="margin-top: 10px">{{ dismissNotice }}</p>

<nav class="glass tabs" role="tablist">
      <button v-for="item in tabs" :key="item.key" class="tab" :class="{ active: tab === item.key }"
              role="tab" @click="tab = item.key">{{ item.label }}</button>
    </nav>

    <div v-if="panelsError" class="notice" style="margin-bottom: 14px">
      页签清单接口不可用（{{ panelsError }}），当前显示内置页签
    </div>

    <component v-if="activeCore" :is="activeCore" :key="'core-' + activeTab.key" v-bind="coreProps"
               v-on="coreEvents"></component>
    <DescriptorPanel v-else-if="activeDescriptor" :key="'descriptor-' + activeDescriptor.key"
                     :tab="activeDescriptor" :tick="tick"></DescriptorPanel>
    <div v-else-if="panelsLoading" class="notice">正在读取页签清单…</div>
    <section v-else-if="activeTab" class="glass panel">
      <div class="panel-head"><h2>{{ activeTab.label }}</h2></div>
      <div class="empty">这个页签在前端没有对应的渲染组件（kind={{ activeTab.kind || '未给' }}）</div>
    </section>

    <div class="status-line" :class="{ stale: Boolean(errorText) }" role="status">{{ statusLine }}</div>
  </div>
</template>
