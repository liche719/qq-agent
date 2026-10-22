<script setup>
/**
 * 花费（2026-10-21）—— **一个地方**看全每个场景的模型用量与金额。
 *
 * 之前只有顶栏数字 + 悬停 tooltip 里的"今天按场景"那几行，token 分布、缓存命中、
 * 历史都被埋在接口里没人看。这个页把 `GET /api/admin/usage?days=N` 的三块摊开：
 * 汇总、按场景、按天。
 *
 * 钱的三种口径都在后端统一算好：token 行按账本、生图按张、语音按字符——
 * 前端只负责显示，不再自己乘单价（否则又是"两处单价"的老坑）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import InfoGrid from '../components/InfoGrid.vue'
import { fmtNum, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const RANGES = [
  { days: 1, label: '今天' },
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' }
]

const days = ref(7)
const data = ref(null)
const error = ref('')
const busy = ref(false)

async function load() {
  busy.value = true
  error.value = ''
  try {
    data.value = await api('/usage?days=' + days.value)
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch(() => props.tick, load)
watch(days, load)

function money(value) {
  return value === null || value === undefined || value === '' ? '—' : '¥' + value
}

function percentText(value) {
  return value === null || value === undefined || value === '' ? '—' : value + '%'
}

const summaryRows = computed(() => {
  const d = data.value || {}
  const scenarios = Array.isArray(d.scenarios) ? d.scenarios : []
  const total = (key) => scenarios.reduce((sum, row) => sum + (Number(row[key]) || 0), 0)
  const prompt = total('promptTokens')
  const hit = total('cacheHitTokens')
  return [
    ['今日花费', money(d.todayYuan)],
    ['区间花费', money(d.rangeYuan)],
    ['调用次数', fmtNum(total('calls'))],
    ['输入 token', fmtNum(prompt)],
    ['输出 token', fmtNum(total('completionTokens'))],
    ['缓存命中率', prompt > 0 ? Math.round(1000 * hit / prompt) / 10 + '%' : '—']
  ]
})

const scenarioColumns = [
  { label: '场景', value: row => zh('scenario', row.scenario) },
  { label: '模型', value: row => row.model || '—' },
  { label: '次数', value: row => fmtNum(row.calls) },
  { label: '输入 token', value: row => fmtNum(row.promptTokens) },
  { label: '输出 token', value: row => fmtNum(row.completionTokens) },
  { label: '缓存命中', value: row => percentText(row.cacheHitPercent) },
  { label: '金额', value: row => money(row.yuan) }
]

const dailyColumns = [
  { label: '日期', value: row => row.day },
  { label: '调用次数', value: row => fmtNum(row.calls) },
  { label: '输入 token', value: row => fmtNum(row.promptTokens) },
  { label: '输出 token', value: row => fmtNum(row.completionTokens) },
  { label: '缓存命中', value: row => percentText(row.cacheHitPercent) },
  { label: '画图张数', value: row => fmtNum(row.imageCalls) },
  { label: '金额', value: row => money(row.yuan) }
]
</script>

<template>
  <div class="stack">
    <section class="glass panel">
      <div class="panel-head">
        <h2>用量与花费</h2>
        <span class="hint">数据来自 llm_call_audit；生图按张、语音按字符折算</span>
      </div>
      <div class="row">
        <button
          v-for="range in RANGES"
          :key="range.days"
          class="btn"
          :class="{ active: days === range.days }"
          :disabled="busy"
          @click="days = range.days"
        >{{ range.label }}</button>
        <span v-if="busy" class="hint">读取中…</span>
        <span v-if="error" class="error">{{ error }}</span>
      </div>
      <InfoGrid :rows="summaryRows"></InfoGrid>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>按场景</h2>
        <span class="hint">钱花在哪一类调用上，多的在前</span>
      </div>
      <DataTable
        :columns="scenarioColumns"
        :rows="(data && data.scenarios) || []"
        empty="这段时间还没有调用记录"
      ></DataTable>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>按天</h2>
        <span class="hint">每天的总量；画图没有 token，单独列出张数</span>
      </div>
      <DataTable
        :columns="dailyColumns"
        :rows="(data && data.daily) || []"
        empty="这段时间还没有记录"
      ></DataTable>
    </section>
  </div>
</template>
