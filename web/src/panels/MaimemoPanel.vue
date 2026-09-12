<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import StatCard from '../components/StatCard.vue'
import InfoGrid from '../components/InfoGrid.vue'
import DataTable from '../components/DataTable.vue'
import JsonBlock from '../components/JsonBlock.vue'
import { fmtNum, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const data = ref(null)
const error = ref('')
const busy = ref('')
const message = ref('')
const tokenInput = ref('')
const pushEnabled = ref(true)
const pushTime = ref('21:30')

const status = computed(() => data.value?.status || '')
const progress = computed(() => data.value?.progress || {})
const todayItems = computed(() => data.value?.todayItems || [])
const records = computed(() => data.value?.records || [])

const tone = computed(() => {
  if (status.value === 'OK') return 'ok'
  if (status.value === 'NOT_CONFIGURED' || status.value === 'DISABLED') return 'warn'
  return 'bad'
})

const tokenRows = computed(() => {
  const rows = [
    ['连接状态', zh('maimemo', status.value)],
    ['Token 来源', data.value?.tokenSource || '—'],
    ['当前 Token', data.value?.tokenHint || '（未配置）'],
    ['Token 更新时间', data.value?.tokenUpdatedAt || '—'],
    ['每次读取', data.value?.checkedAt ? '数据时间 ' + data.value.checkedAt : '—']
  ]
  return rows
})

const pushRows = computed(() => {
  const push = data.value?.push || {}
  return [
    ['每日推送', push.enabled ? '已开启' : '已关闭'],
    ['推送时间', push.time || '—'],
    ['最近推送日期', push.lastPushDate || '—'],
    ['推送对象', push.ready ? '本人 QQ（alert.qq-openid）' : '未就绪：缺 Token 或 QQ 通道']
  ]
})

const itemColumns = [
  { label: '序号', key: 'order' },
  { label: '单词', key: 'spelling', mono: true },
  { label: '类型', value: row => (row.isNew ? '新学' : '复习') },
  {
    label: '状态',
    tag: row => (row.finished ? { tone: 'ok', text: '已学' } : { tone: 'warn', text: '待学' })
  },
  { label: '首次反应', value: row => row.firstResponse || '—' }
]

const recordColumns = [
  { label: '单词', key: 'spelling', mono: true },
  { label: '复习次数', key: 'studyCount' },
  { label: '最近反应', value: row => row.lastResponse || '—' },
  { label: '下次复习', key: 'nextStudyDate' },
  {
    label: '标记',
    tag: row => (row.sticking ? { tone: 'bad', text: '顽固' } : { tone: 'ok', text: '正常' })
  }
]

async function load() {
  try {
    data.value = await api('/maimemo/overview')
    syncForm()
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  }
}

function syncForm() {
  pushEnabled.value = Boolean(data.value?.push?.enabled)
  pushTime.value = data.value?.push?.time || '21:30'
}

async function run(action, path, body, done) {
  busy.value = action
  message.value = ''
  try {
    const result = await api(path, { method: 'POST', body })
    if (result.accepted === false || result.saved === false) throw new Error(result.message || '操作未被接受')
    message.value = result.message || done || '操作已执行'
    if (result.status || result.progress) {
      data.value = result
      syncForm()
    } else {
      await load()
    }
  } catch (caught) {
    message.value = '操作失败：' + caught.message
  } finally {
    busy.value = ''
  }
}

function saveToken() {
  const value = tokenInput.value.trim()
  if (!value && !window.confirm('清空面板里保存的 Token？清空后将回落到服务器环境变量里的 Token。')) return
  run('token', '/maimemo/token', { token: value }, 'Token 已保存').then(() => { tokenInput.value = '' })
}

function savePush() {
  run('push-settings', '/maimemo/push/settings', { enabled: pushEnabled.value, time: pushTime.value }, '推送设置已保存')
}

watch(() => props.tick, () => load())
onMounted(() => load())
</script>

<template>
  <div class="stack">
    <div class="grid-4">
      <StatCard label="今日进度" :value="fmtNum(progress.finished) + ' / ' + fmtNum(progress.total)"
                :meta="'完成 ' + fmtNum(progress.percent) + '%'" :tone="tone"></StatCard>
      <StatCard label="还剩" :value="fmtNum(progress.remaining)" meta="今日待背单词"></StatCard>
      <StatCard label="新学 / 复习" :value="fmtNum(progress.newCount) + ' / ' + fmtNum(progress.reviewCount)"
                meta="今天已学单词构成"></StatCard>
      <StatCard label="学习时长" :value="fmtNum(progress.studyTimeMinutes) + ' 分钟'" meta="墨墨统计"></StatCard>
    </div>

    <section class="glass panel">
      <div class="panel-head">
        <h2>墨墨背单词</h2>
        <span class="hint">数据来自墨墨开放 API，聊天里问「今天背了多少」也读这里</span>
      </div>
      <InfoGrid :rows="tokenRows"></InfoGrid>
      <p v-if="data?.message" class="hint" style="margin-top: 12px">{{ data.message }}</p>

      <div class="toolbar">
        <input v-model="tokenInput" type="password" autocomplete="off" spellcheck="false"
               placeholder="粘贴墨墨 App「开放 API」里生成的 Token">
        <button class="btn btn-sm" :disabled="busy === 'token'" @click="saveToken">
          {{ tokenInput ? '保存 Token' : '清除 Token' }}
        </button>
        <button class="btn btn-sm" :disabled="busy === 'refresh'"
                @click="run('refresh', '/maimemo/refresh', undefined, '已重新读取')">立即读取</button>
        <span v-if="message" class="hint">{{ message }}</span>
      </div>
      <p class="hint" style="margin-top: 8px">
        Token 有效期很短（墨墨 App 里显示一天左右），过期后聊天查询会提示失效；
        把新 Token 粘到这里保存即可，不需要登录服务器改配置。
      </p>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>每日推送</h2>
        <span class="hint">到点给你的 QQ 发一条今日背单词进度</span>
      </div>
      <InfoGrid :rows="pushRows"></InfoGrid>
      <div class="toolbar">
        <label class="check" style="margin-top: 0">
          <input type="checkbox" v-model="pushEnabled">
          <span>{{ pushEnabled ? '已开启每日推送' : '已关闭每日推送' }}</span>
        </label>
        <input v-model="pushTime" type="time" step="60" aria-label="推送时间">
        <button class="btn btn-sm" :disabled="busy === 'push-settings'" @click="savePush">保存设置</button>
        <button class="btn btn-sm" :disabled="busy === 'push-now'"
                @click="run('push-now', '/maimemo/push/now', undefined, '已推送')">立即推送一次</button>
      </div>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>今日单词</h2>
        <span class="hint">按墨墨的排序，最多 30 个</span>
      </div>
      <DataTable :columns="itemColumns" :rows="todayItems" empty="今天还没有学习任务"></DataTable>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>学习记录</h2>
        <span class="hint">复习次数与下次复习日期，顽固＝反复标记忘记</span>
      </div>
      <DataTable :columns="recordColumns" :rows="records" empty="暂无学习记录"></DataTable>
    </section>

    <section class="glass panel">
      <div class="panel-head"><h2>原始数据</h2><span class="hint">接口原样返回，排障用</span></div>
      <p v-if="error" class="hint" style="color: var(--bad-ink)">{{ error }}</p>
      <JsonBlock title="展开墨墨接口数据" :data="data"></JsonBlock>
    </section>
  </div>
</template>
