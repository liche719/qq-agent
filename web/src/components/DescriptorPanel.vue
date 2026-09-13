<script setup>
/**
 * 通用描述式面板：按后端给的「区块描述」渲染页签内容，前端不认识具体模块。
 *
 * 支持的区块（按 sections 顺序渲染，每个区块各管各的 loading/error/数据）：
 *   info     { rows: [{label, value}] }         → InfoGrid
 *   table    { rows: [{...}], rowActions: [] }  → DataTable（列的 wide/mono/tag 语义与手写面板一致；
 *                                                  rowActions 是行内小按钮，body 里的 $列名 换成该行的值）
 *   bars     { items: [{label, value}] }        → 复用 style.css 的 .chart* 柱状（unit 是数值后缀）
 *   form     { fields, endpoint, initialEndpoint } → 后端描述驱动的表单（type: text/textarea/number/date/select）
 *   actions  由 actions[].endpoint 触发 POST，响应 {message}，accepted === false 视为失败
 * endpoint 给的是完整路径（/api/admin/xxx），这里剥掉前缀交给 api() 拼。
 * 所有文案（标题/label/placeholder/options/按钮名）都来自后端描述，这里不写任何模块词。
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
const FALLBACK_TITLES = { info: '信息', table: '数据', bars: '统计', form: '表单', actions: '操作' }

const sections = computed(() => (Array.isArray(props.tab?.sections) ? props.tab.sections : []))
/** 与 sections 同下标，每个区块一份 { loading, error, data } */
const states = ref([])
/** 各区块的提示文案（动作区/表单/行内操作共用），按区块下标存放 */
const notes = ref({})
/** 动作区的忙碌标记：'区块下标:按钮名' */
const busy = ref('')
/** 表单当前编辑值：区块下标 → { 字段key: 值 }，预填/重拉时整体替换 */
const formValues = ref({})
/** 表单提交中：区块下标 → true（一次提交是整表单一个动作） */
const formBusy = ref({})
/** 行内按钮忙碌标记：区块下标 → { '行下标:按钮下标': true }（行与行、按钮与按钮互不影响） */
const rowBusy = ref({})
/** 用户动过的表单：区块下标 → true。动过之后自动刷新不再回写预填值，否则会把正在编辑的内容冲掉 */
const formTouched = ref({})
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

/* ---------------- 表单（kind=form） ---------------- */

/** 后端描述的字段：没给 type 一律当 text；select 没给 options 时退化成文本框，免得出现选不了的空下拉 */
function toFields(fields) {
  if (!Array.isArray(fields)) return []
  return fields.filter(field => field && field.key).map(field => {
    const type = String(field.type || 'text').toLowerCase()
    const options = Array.isArray(field.options)
      ? field.options.map(option => ({
        value: option?.value === undefined || option?.value === null ? '' : String(option.value),
        label: option?.label === undefined || option?.label === null ? String(option?.value ?? '') : String(option.label)
      }))
      : []
    return {
      key: field.key,
      label: field.label || field.key,
      type: type === 'select' && !options.length ? 'text' : type,
      inputType: type === 'number' ? 'number' : (type === 'date' ? 'date' : 'text'),
      placeholder: field.placeholder || '',
      options
    }
  })
}

/** 预填值一律转成字符串（input 的 v-model 就是字符串），null/undefined 当空 */
function toFieldValues(fields, values) {
  const source = values && typeof values === 'object' && !Array.isArray(values) ? values : {}
  const result = {}
  for (const field of fields) {
    const raw = source[field.key]
    result[field.key] = raw === undefined || raw === null ? '' : String(raw)
  }
  return result
}

/** 提交体：各字段当前值组成一个对象；数字字段用 Number()，空字符串原样传（后端自己判断"清空"） */
function fieldBody(view) {
  const values = formValues.value[view.index] || {}
  const body = {}
  for (const field of view.fields) {
    const raw = values[field.key]
    const text = raw === undefined || raw === null ? '' : String(raw)
    if (field.type === 'number' && text.trim() !== '') {
      const parsed = Number(text.trim())
      // 填了非数字就原样传，让后端给出明确报错，而不是悄悄变成 null
      body[field.key] = Number.isFinite(parsed) ? parsed : text
    } else {
      body[field.key] = text
    }
  }
  return body
}

/* ---------------- 行内操作（table.rowActions） ---------------- */

/** body 里形如 $列名 的字符串换成该行该列的值（$$ 是字面量 $，其它内容一律不动） */
function resolveTemplate(text, row) {
  let out = ''
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== '$') {
      out += text[i]
      continue
    }
    if (text[i + 1] === '$') {
      out += '$'
      i += 1
      continue
    }
    let end = i + 1
    while (end < text.length && /[A-Za-z0-9_]/.test(text[end])) end += 1
    const name = text.slice(i + 1, end)
    // 列名对不上就原样留着，方便一眼看出描述写错了
    if (!name || !row || !Object.prototype.hasOwnProperty.call(row, name)) {
      out += '$'
      continue
    }
    const value = row[name]
    // 替换结果保持字符串，数值也一样（后端自己解析）
    out += value === undefined || value === null ? '' : String(value)
    i = end - 1
  }
  return out
}

function resolveBody(body, row) {
  if (typeof body === 'string') return resolveTemplate(body, row)
  if (Array.isArray(body)) return body.map(item => resolveBody(item, row))
  if (body && typeof body === 'object') {
    const result = {}
    for (const [key, value] of Object.entries(body)) result[key] = resolveBody(value, row)
    return result
  }
  return body
}

/** 交给 DataTable 的只有按钮外观，真正的 endpoint/body/confirm 由本组件按同下标取回 */
function toRowActions(actions) {
  if (!Array.isArray(actions)) return []
  return actions.map(action => ({
    label: action?.label || '执行',
    confirm: action?.confirm || ''
  }))
}

function setRowBusy(index, key, value) {
  const current = { ...(rowBusy.value[index] || {}) }
  if (value) current[key] = true
  else delete current[key]
  rowBusy.value = { ...rowBusy.value, [index]: current }
}

function fieldError(view) {
  if (!view.fields.length) return '描述里没有给出可填写的字段'
  if (!toPath(view.endpoint)) return '描述里没有给出接口地址'
  return ''
}

/* ---------------- 数据加载 ---------------- */

/** 单个区块取数：表单拿的是预填值，其余拿 endpoint 的数据；失败只影响它自己 */
async function fetchOne(section) {
  // 动作区不需要先拉数据，按钮直接可点
  if (section?.kind === 'actions') return { loading: false, error: '', data: null }

  const path = toPath(section?.endpoint)
  if (!path) return { loading: false, error: '描述里没有给出接口地址', data: null }

  if (section?.kind === 'form') {
    const fields = toFields(section.fields)
    if (!fields.length) return { loading: false, error: '描述里没有给出可填写的字段', data: null, values: {} }
    let values = toFieldValues(fields, null)
    const initial = toPath(section?.initialEndpoint)
    if (initial) {
      try {
        // 预填失败（接口没上线/出错）就留空，不刷错误提示
        const data = await api(initial)
        values = toFieldValues(fields, data?.values)
      } catch (ignored) {
        /* 留空 */
      }
    }
    return { loading: false, error: '', data: null, values }
  }

  try {
    return { loading: false, error: '', data: await api(path) }
  } catch (error) {
    // 单个区块失败只影响它自己，其它区块照常显示
    return { loading: false, error: error.message, data: null }
  }
}

function applyState(index, state, current) {
  if (current !== sequence) return
  const prev = states.value[index]
  const next = { ...state }
  // 刷新失败时保留上一次的数据：别把已经显示出来的内容换成一行报错
  if (state.error && prev && prev.data !== null) next.data = prev.data
  states.value[index] = next
  // 表单只在「用户没动过」时才回写预填值，不然自动刷新会把正在编辑的内容冲掉
  if (state.values && !formTouched.value[index]) {
    formValues.value = { ...formValues.value, [index]: state.values }
  }
}

/** 用户开始编辑这个表单了（input/change 冒泡上来） */
function markTouched(index) {
  if (formTouched.value[index]) return
  formTouched.value = { ...formTouched.value, [index]: true }
}

/**
 * 拉所有区块。**自动刷新时不清空旧数据**：原来每次都把状态重置成 loading + data:null，
 * 于是每刷新一次，整个区块就被「加载中…」替掉、再重建一次 —— 看上去就跟整页刷新一样闪。
 * 现在沿用上一次的状态（保留旧数据），只有第一次进来才显示「加载中…」。
 */
async function load() {
  const current = ++sequence
  const list = sections.value
  const previous = states.value
  states.value = list.map((section, index) => {
    const prev = previous[index]
    return prev ? { ...prev, loading: false } : { loading: true, error: '', data: null }
  })
  await Promise.all(list.map(async (section, index) => {
    applyState(index, await fetchOne(section), current)
  }))
}

/** 只重拉一个区块（表单提交 / 行内操作成功后）：其它区块保持原样，不整页闪「加载中」 */
async function reloadSection(index) {
  const current = sequence
  const section = sections.value[index]
  if (!section) return
  applyState(index, await fetchOne(section), current)
}

/* ---------------- 操作 ---------------- */

/** 后端明确说没接受就按失败提示（与手写面板一致） */
function ensureAccepted(result) {
  if (result && result.accepted === false) throw new Error(result.message || '操作未被接受')
  return (result && result.message) || '操作已执行'
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
    notes.value = { ...notes.value, [view.index]: ensureAccepted(result) }
    await load()
  } catch (error) {
    notes.value = { ...notes.value, [view.index]: '操作失败：' + error.message }
  } finally {
    busy.value = ''
  }
}

/** 表单提交：POST 到 endpoint，成功后按后端最新值重新预填 */
async function submit(view) {
  if (formBusy.value[view.index]) return
  const problem = fieldError(view)
  if (problem) {
    notes.value = { ...notes.value, [view.index]: '操作失败：' + problem }
    return
  }
  notes.value = { ...notes.value, [view.index]: '' }
  formBusy.value = { ...formBusy.value, [view.index]: true }
  try {
    const result = await api(toPath(view.endpoint), { method: view.method, body: fieldBody(view) })
    notes.value = { ...notes.value, [view.index]: ensureAccepted(result) }
    // 存过了就以服务端为准：清掉「动过」标记，让重拉回来的值覆盖表单
    formTouched.value = { ...formTouched.value, [view.index]: false }
    await reloadSection(view.index)
  } catch (error) {
    notes.value = { ...notes.value, [view.index]: '操作失败：' + error.message }
  } finally {
    const next = { ...formBusy.value }
    delete next[view.index]
    formBusy.value = next
  }
}

/** 行内按钮：confirm → POST（body 里的 $列名 换成该行值）→ 提示 message → 重拉这个区块 */
async function runRow(view, action, row, rowIndex, actionIndex) {
  const key = rowIndex + ':' + actionIndex
  if (rowBusy.value[view.index]?.[key]) return
  if (action?.confirm && !window.confirm(action.confirm)) return
  setRowBusy(view.index, key, true)
  notes.value = { ...notes.value, [view.index]: '' }
  try {
    const path = toPath(action?.endpoint || view.endpoint)
    if (!path) throw new Error('描述里没有给出接口地址')
    const result = await api(path, {
      method: String(action?.method || 'POST').toUpperCase(),
      body: resolveBody(action?.body, row)
    })
    notes.value = { ...notes.value, [view.index]: ensureAccepted(result) }
    await reloadSection(view.index)
  } catch (error) {
    notes.value = { ...notes.value, [view.index]: '操作失败：' + error.message }
  } finally {
    setRowBusy(view.index, key, false)
  }
}

/** DataTable 只回传「第几行第几个按钮」，真正的描述按同下标从 sections 里取 */
function onRowAction(view, payload) {
  const action = sections.value[view.index]?.rowActions?.[payload.actionIndex]
  return runRow(view, action, payload.row, payload.index, payload.actionIndex)
}

function busyKey(view, action) {
  return view.index + ':' + (action?.label || '')
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
    method: String(section?.method || 'POST').toUpperCase(),
    loading: Boolean(state.loading),
    error: state.error || '',
    hasData: state.data !== null && state.data !== undefined,
    infoRows: toInfoRows(state),
    columns: toColumns(section?.columns),
    rows: Array.isArray(state?.data?.rows) ? state.data.rows : [],
    empty: section?.empty || '暂无数据',
    /** 后端会在「未配置」等情况下带一句 message，手写面板也会显示它 */
    message: state?.data?.message || '',
    bars: toBars(state, section?.unit || ''),
    actions: Array.isArray(section?.actions) ? section.actions : [],
    fields: toFields(section?.fields),
    values: formValues.value[index] || {},
    formBusy: Boolean(formBusy.value[index]),
    submitLabel: section?.submitLabel || '保存',
    rowActions: toRowActions(section?.rowActions),
    rowBusy: rowBusy.value[index] || {},
    note: notes.value[index] || '',
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

watch(() => props.tick, () => load())
watch(() => props.tab?.key, () => {
  notes.value = {}
  formBusy.value = {}
  rowBusy.value = {}
  formTouched.value = {}
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
      <p v-else-if="view.error && !view.hasData" class="hint" style="margin-top: 8px; color: var(--bad-ink)">{{ view.error }}</p>

      <template v-else-if="view.kind === 'info'">
        <div v-if="!view.infoRows.length" class="empty">{{ view.empty }}</div>
        <InfoGrid v-else :rows="view.infoRows"></InfoGrid>
      </template>

      <template v-else-if="view.kind === 'table'">
        <DataTable :columns="view.columns" :rows="view.rows" :empty="view.empty"
                   :row-actions="view.rowActions" :row-busy="view.rowBusy"
                   @row-action="payload => onRowAction(view, payload)"></DataTable>
        <p v-if="view.note" class="hint" style="margin-top: 8px">{{ view.note }}</p>
      </template>

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

      <form v-else-if="view.kind === 'form'" @submit.prevent="submit(view)"
            @input="markTouched(view.index)" @change="markTouched(view.index)">
        <div class="form-grid">
          <div v-for="field in view.fields" :key="field.key" class="field"
               :class="{ full: field.type === 'textarea' }">
            <label :for="'field-' + view.index + '-' + field.key">{{ field.label }}</label>
            <textarea v-if="field.type === 'textarea'" :id="'field-' + view.index + '-' + field.key"
                      v-model="view.values[field.key]" :placeholder="field.placeholder" rows="3"></textarea>
            <select v-else-if="field.type === 'select'" :id="'field-' + view.index + '-' + field.key"
                    v-model="view.values[field.key]">
              <option value=""></option>
              <option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <input v-else :id="'field-' + view.index + '-' + field.key" v-model="view.values[field.key]"
                   :type="field.inputType" :placeholder="field.placeholder">
          </div>
        </div>
        <div class="toolbar">
          <button class="btn btn-primary" type="submit" :disabled="view.formBusy">
            {{ view.formBusy ? '保存中…' : view.submitLabel }}
          </button>
          <span v-if="view.note" class="hint">{{ view.note }}</span>
        </div>
      </form>

      <div v-else-if="view.kind === 'actions'" class="toolbar">
        <button v-for="(action, actionIndex) in view.actions" :key="actionIndex" class="btn btn-sm"
                :disabled="busy === busyKey(view, action)" @click="run(view, action)">
          {{ action.label || '执行' }}
        </button>
        <span v-if="view.note" class="hint">{{ view.note }}</span>
      </div>

      <p v-else class="hint" style="margin-top: 8px">不认识的区块类型：{{ view.kind || '（空）' }}</p>

      <p v-if="!view.loading && view.error && view.hasData" class="hint" style="margin-top: 8px; color: var(--bad-ink)">
        这次刷新失败，显示的是上一次的数据：{{ view.error }}
      </p>
      <p v-if="!view.loading && !view.error && view.message" class="hint" style="margin-top: 8px">{{ view.message }}</p>
    </section>
  </div>
</template>
