<script setup>
/**
 * 通用描述式面板：按后端给的「区块描述」渲染页签内容，前端不认识具体模块。
 *
 * 支持的区块（按 sections 顺序渲染，每个区块各管各的 loading/error/数据）：
 *   info    { rows: [{label, value}] }     → InfoGrid
 *   table   { rows: [{...}] }              → DataTable（列的 wide/mono/tag 语义与手写面板一致）
 *   bars    { items: [{label, value}] }    → 复用 style.css 的 .chart* 柱状（unit 是数值后缀）
 *   actions 由 actions[].endpoint 触发 POST，响应 {message}，accepted === false 视为失败
 * endpoint 给的是完整路径（/api/admin/xxx），这里剥掉前缀交给 api() 拼。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import InfoGrid from '../components/InfoGrid.vue'

const props = defineProps({
  tab: { type: Object, required: true },
  tick: { type: Number, default: 0 }
})

/** 后端没给 title 时的中性标题（模块文案一律由后端描述提供，这里只是兜底） */
const FALLBACK_TITLES = { info: '信息', table: '数据', bars: '统计', actions: '操作' }

const sections = computed(() => (Array.isArray(props.tab?.sections) ? props.tab.sections : []))
/** 与 sections 同下标，每个区块一份 { loading, error, data } */
const states = ref([])
/** 动作区的提示文案，按区块下标存放 */
const notes = ref({})
const busy = ref('')
/** 只认最后一次请求，切页签时慢响应回来不能覆盖新数据 */
let sequence = 0

/** 描述里的 endpoint 是完整路径，api() 会自己加 /api/admin 前缀 */
function toPath(endpoint) {
  const text = String(endpoint || '').trim()
  if (!text) return ''
  if (text === '/api/admin') return '/'
  if (text.startsWith('/api/admin/')) return text.slice('/api/admin'.length)
  return text.startsWith('/') ? text : '/' + text
}

function display(value) {
  if (value === undefined || value === null || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return value
}

/** info：{rows:[{label,value}]}，也容忍后端直接给 [label, value] 数组 */
function toInfoRows(state) {
  const rows = state?.data?.rows
  if (!Array.isArray(rows)) return []
  return rows.map(row => (Array.isArray(row) ? [row[0], display(row[1])] : [row?.label ?? '', display(row?.value)]))
}

/** tag 是「原值 → {text,tone}」映射；没配映射（或原值不在映射里）就原样显示 */
function toTag(map, value) {
  const entry = map ? map[String(value)] : null
  if (typeof entry === 'string') return { text: entry, tone: '' }
  if (entry && typeof entry === 'object') return { text: entry.text ?? String(value), tone: entry.tone || '' }
  return { text: display(value), tone: '' }
}

function toColumns(columns) {
  if (!Array.isArray(columns)) return []
  return columns.map(column => ({
    label: column?.label || column?.key || '',
    key: column?.key,
    mono: Boolean(column?.mono),
    wide: Boolean(column?.wide),
    tag: column?.tag ? row => toTag(column.tag, row?.[column.key]) : undefined
  }))
}

function toBars(state, unit) {
  const items = Array.isArray(state?.data?.items) ? state.data.items : []
  const peak = items.reduce((best, item) => Math.max(best, Number(item?.value) || 0), 0)
  return {
    items,
    peak,
    axis: items.length
      ? [display(items[0]?.label), '峰值 ' + display(peak) + (unit || ''), display(items[items.length - 1]?.label)]
      : []
  }
}

/** 一次算好模板要用的东西，避免在模板里反复调函数 */
const views = computed(() => sections.value.map((section, index) => {
  const state = states.value[index] || { loading: true, error: '', data: null }
  return {
    key: section?.key || String(index),
    title: section?.title || FALLBACK_TITLES[section?.kind] || '区块',
    hint: section?.hint || '',
    kind: section?.kind,
    unit: section?.unit || '',
    endpoint: section?.endpoint || '',
    loading: Boolean(state.loading),
    error: state.error || '',
    infoRows: toInfoRows(state),
    columns: toColumns(section?.columns),
    rows: Array.isArray(state?.data?.rows) ? state.data.rows : [],
    empty: section?.empty || '暂无数据',
    /** 后端会在「未配置」等情况下带一句 message，手写面板也会显示它 */
    message: state?.data?.message || '',
    bars: toBars(state, section?.unit || ''),
    actions: Array.isArray(section?.actions) ? section.actions : [],
    index
  }
}))

function barHeight(peak, item) {
  const value = Number(item?.value) || 0
  return Math.max(3, value / Math.max(1, peak) * 100) + '%'
}

function barTip(item, unit) {
  return display(item?.label) + ' · ' + display(item?.value) + (unit || '')
}

async function load() {
  const current = ++sequence
  const list = sections.value
  states.value = list.map(() => ({ loading: true, error: '', data: null }))
  await Promise.all(list.map(async (section, index) => {
    // 动作区不需要先拉数据，按钮直接可点
    if (section?.kind === 'actions') {
      if (current === sequence) states.value[index] = { loading: false, error: '', data: null }
      return
    }
    const path = toPath(section?.endpoint)
    // 数据区缺 endpoint 说明后端描述不完整，如实提示而不是显示成「暂无数据」
    if (!path) {
      if (current === sequence) states.value[index] = { loading: false, error: '描述里没有给出接口地址', data: null }
      return
    }
    try {
      const data = await api(path)
      if (current === sequence) states.value[index] = { loading: false, error: '', data }
    } catch (error) {
      // 单个区块失败只影响它自己，其它区块照常显示
      if (current === sequence) states.value[index] = { loading: false, error: error.message, data: null }
    }
  }))
}

async function run(view, action) {
  if (busy.value) return
  if (action?.confirm && !window.confirm(action.confirm)) return
  const key = view.index + ':' + (action?.label || '')
  busy.value = key
  notes.value = { ...notes.value, [view.index]: '' }
  try {
    const path = toPath(action?.endpoint || view.endpoint)
    if (!path) throw new Error('描述里没有给出接口地址')
    const result = await api(path, {
      method: String(action?.method || 'POST').toUpperCase(),
      body: action?.body
    })
    // 与手写面板一致：后端明确说没接受就按失败提示
    if (result && result.accepted === false) throw new Error(result.message || '操作未被接受')
    notes.value = { ...notes.value, [view.index]: (result && result.message) || '操作已执行' }
    await load()
  } catch (error) {
    notes.value = { ...notes.value, [view.index]: '操作失败：' + error.message }
  } finally {
    busy.value = ''
  }
}

function busyKey(view, action) {
  return view.index + ':' + (action?.label || '')
}

watch(() => props.tick, () => load())
watch(() => props.tab?.key, () => {
  notes.value = {}
  load()
})
onMounted(() => load())
</script>

<template>
  <div class="stack">
    <section v-if="!views.length" class="glass panel">
      <div class="panel-head"><h2>{{ tab.label || tab.key }}</h2><span class="hint">后端没有给出可渲染的区块</span></div>
      <div class="empty">这个页签暂时没有内容</div>
    </section>

    <section v-for="view in views" :key="view.key" class="glass panel">
      <div class="panel-head">
        <h2>{{ view.title }}</h2>
        <span v-if="view.hint" class="hint">{{ view.hint }}</span>
      </div>

      <div v-if="view.loading" class="empty">加载中…</div>
      <p v-else-if="view.error" class="hint" style="margin-top: 8px; color: var(--bad-ink)">{{ view.error }}</p>

      <template v-else-if="view.kind === 'info'">
        <div v-if="!view.infoRows.length" class="empty">{{ view.empty }}</div>
        <InfoGrid v-else :rows="view.infoRows"></InfoGrid>
      </template>

      <DataTable v-else-if="view.kind === 'table'" :columns="view.columns" :rows="view.rows"
                 :empty="view.empty"></DataTable>

      <template v-else-if="view.kind === 'bars'">
        <div v-if="!view.bars.items.length" class="empty">{{ view.empty }}</div>
        <template v-else>
          <div class="chart">
            <div class="chart-bars">
              <i v-for="(item, itemIndex) in view.bars.items" :key="itemIndex"
                 class="chart-bar" :style="{ height: barHeight(view.bars.peak, item) }"
                 :title="barTip(item, view.unit)"></i>
            </div>
          </div>
          <div class="chart-axis">
            <span v-for="(text, axisIndex) in view.bars.axis" :key="axisIndex">{{ text }}</span>
          </div>
        </template>
      </template>

      <div v-else-if="view.kind === 'actions'" class="toolbar">
        <button v-for="(action, actionIndex) in view.actions" :key="actionIndex" class="btn btn-sm"
                :disabled="busy === busyKey(view, action)" @click="run(view, action)">
          {{ action.label || '执行' }}
        </button>
        <span v-if="notes[view.index]" class="hint">{{ notes[view.index] }}</span>
      </div>

      <p v-else class="hint" style="margin-top: 8px">不认识的区块类型：{{ view.kind || '（空）' }}</p>

      <p v-if="!view.loading && !view.error && view.message" class="hint" style="margin-top: 8px">{{ view.message }}</p>
    </section>
  </div>
</template>
