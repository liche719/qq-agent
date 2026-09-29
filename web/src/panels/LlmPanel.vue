<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import InfoGrid from '../components/InfoGrid.vue'
import JsonBlock from '../components/JsonBlock.vue'
import { fmtMs, fmtNum, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const data = ref(null)
const error = ref('')

// 思考强度滑块（每个场景一个）。档位顺序就是滑块的 4 个位置：0=默认（不传上游默认档）
const EFFORT_STEPS = ['', 'low', 'medium', 'high']
const efforts = ref(null)
const effortMessage = ref('')
const effortBusy = ref('')

function percent(value) {
  return value === null || value === undefined ? '—' : value + '%'
}

function ms(value) {
  return value === null || value === undefined ? '—' : fmtMs(value)
}

function positionOf(effort) {
  const index = EFFORT_STEPS.indexOf(effort || '')
  return index < 0 ? 0 : index
}

function effortText(effort) {
  return effort ? zh('effort', effort) : '默认'
}

const llmRows = computed(() => {
  const llm = data.value?.llm || {}
  const stream = data.value?.llmStream || {}
  return [
    ['一次性调用次数', fmtNum(llm.calls)],
    ['一次性调用失败', fmtNum(llm.failures)],
    ['一次性成功率', percent(llm.successRate)],
    ['一次性平均耗时', ms(llm.averageMs)],
    ['对话流式调用次数', fmtNum(stream.calls)],
    ['对话流式失败', fmtNum(stream.failures)],
    ['对话成功率', percent(stream.successRate)],
    ['对话平均耗时', ms(stream.averageMs)]
  ]
})

const searchRows = computed(() => {
  const search = data.value?.search || {}
  return [
    ['搜索次数', fmtNum(search.calls)],
    ['搜索失败', fmtNum(search.failures)],
    ['无结果次数', fmtNum(search.emptyResults)],
    ['搜索成功率', percent(search.successRate)],
    ['平均耗时', ms(search.averageMs)],
    ['最近一次耗时', ms(search.lastMs)]
  ]
})

const scenarioColumns = [
  { label: '调用场景', value: (row) => zh('scenario', row.key) },
  { label: '次数', value: (row) => fmtNum(row.calls) },
  { label: '失败', value: (row) => fmtNum(row.failures) },
  { label: '平均耗时', value: (row) => (row.averageMs === null || row.averageMs === undefined ? '—' : fmtMs(row.averageMs)) },
  { label: '输入 token', value: (row) => fmtNum(row.promptTokens) },
  { label: '输出 token', value: (row) => fmtNum(row.completionTokens) },
  { label: '其中思考', value: (row) => fmtNum(row.reasoningTokens) }
]

const scenarioRows = computed(() => {
  const map = data.value?.llmByScenario || {}
  return Object.keys(map).map((key) => Object.assign({ key }, map[key]))
})

const effortRows = computed(() => efforts.value?.scenarios || [])

const lastErrors = computed(() => {
  const rows = []
  const llm = data.value?.llm || {}
  const search = data.value?.search || {}
  if (llm.lastError) rows.push(['模型最近错误', llm.lastErrorAt + '  ' + llm.lastError])
  if (search.lastError) rows.push(['搜索最近错误', search.lastErrorAt + '  ' + search.lastError])
  return rows
})

async function load() {
  try {
    data.value = await api('/metrics/runtime')
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  }
}

// 只在进入页面时读一次：滑块是用户正在编辑的东西，**不能**跟着 10 秒的 tick 被冲掉（坑 57 同款教训）
async function loadEfforts() {
  try {
    efforts.value = await api('/llm/scenarios')
  } catch (caught) {
    error.value = caught.message
  }
}

function onSlide(row, position) {
  row.effective = EFFORT_STEPS[Number(position)] || ''
}

/**
 * 手势守卫（2026-09-29 踩到之后加的）。
 *
 * `touch-action: pan-y` **不够**：range 控件在手指按下（touchstart）那一刻就已经把值跳到手指位置了，
 * 等浏览器判定"这是竖向滚动"时值已经变了，松手照样触发 change。实测：在手机上竖向滑动滚页面，
 * 一次误写了 5 行覆盖值。
 *
 * 所以自己判定：按下时记下坐标与**原值**，松手时看这一次划动是横还是竖——
 * 竖着的（|dy| > |dx|）一律当"用户在滚页面"，把滑块拨回原值，不写库。
 */
let gesture = null

function onSliderDown(row, event) {
  gesture = { x: event.clientX, y: event.clientY, before: row.override || '' }
}

function onSliderUp(row, event) {
  const started = gesture
  gesture = null
  if (!started) {
    return
  }
  const dx = Math.abs(event.clientX - started.x)
  const dy = Math.abs(event.clientY - started.y)
  if (dy > dx) {
    row.effective = started.before
    if (event.target) {
      event.target.value = String(positionOf(started.before))
    }
    return
  }
  saveEffort(row, event.target ? event.target.value : String(positionOf(row.effective)))
}

async function saveEffort(row, position) {
  const effort = EFFORT_STEPS[Number(position)] || ''
  const current = row.override || ''
  // 值没变就不写库：误触常常只是"拨到了同一个位置"，这种空写会把「跟随配置」
  // 悄悄变成「面板设定」，让界面说谎。键盘操作（没有指针事件）也走这条兜底。
  if (effort === current) {
    row.effective = current
    return
  }
  if (effortBusy.value) return
  effortBusy.value = row.scenario
  try {
    efforts.value = await api('/llm/scenarios', {
      method: 'POST',
      body: { scenario: row.scenario, effort }
    })
    effortMessage.value = zh('scenario', row.scenario) + ' 已设为「' + effortText(effort) + '」，下一次调用就生效'
  } catch (caught) {
    effortMessage.value = caught.message
    await loadEfforts()
  } finally {
    effortBusy.value = ''
  }
}

watch(() => props.tick, () => load())
onMounted(() => {
  load()
  loadEfforts()
})
</script>

<template>
  <div class="stack">
    <div class="grid-2">
      <section class="glass panel">
        <div class="panel-head"><h2>模型调用</h2><span class="hint">记忆提取、提醒解析与对话回复</span></div>
        <InfoGrid :rows="llmRows"></InfoGrid>
      </section>
      <section class="glass panel">
        <div class="panel-head"><h2>搜索</h2><span class="hint">自托管 SearX-NG 的调用情况</span></div>
        <InfoGrid :rows="searchRows"></InfoGrid>
      </section>
    </div>

    <section class="glass panel">
      <div class="panel-head">
        <h2>思考强度</h2>
        <span class="hint">每个场景单独设置：越靠右想得越多、越慢也越贵；「默认」= 不传这个参数、用上游默认档</span>
      </div>
      <div v-for="row in effortRows" :key="row.scenario" class="effort-row">
        <div class="effort-name">
          {{ zh('scenario', row.scenario) }}
          <small>{{ row.fromPanel ? '面板设定' : '跟随配置' }} · 配置默认 {{ effortText(row.configured) }}</small>
        </div>
        <input
          class="effort-slider"
          type="range"
          min="0"
          max="3"
          step="1"
          :value="positionOf(row.effective)"
          :disabled="effortBusy === row.scenario"
          @input="onSlide(row, $event.target.value)"
          @pointerdown="onSliderDown(row, $event)"
          @pointerup="onSliderUp(row, $event)"
          @pointercancel="onSliderUp(row, $event)"
          @change="saveEffort(row, $event.target.value)"
        />
        <div class="effort-value">{{ effortText(row.effective) }}</div>
      </div>
      <p v-if="effortMessage" class="hint" style="margin-top: 10px">{{ effortMessage }}</p>
      <p class="hint" style="margin-top: 10px">
        改完立刻生效、不用重启容器（值存在库里，重启也还在）。调高「记忆提取」会更认真但更贵；
        调低「对话」回复更快、更像随口聊天。
      </p>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>按场景</h2>
        <span class="hint">进程内累计，重启归零；「其中思考」是输出 token 里花在思考上的部分（受上面的滑块影响）</span>
      </div>
      <DataTable :columns="scenarioColumns" :rows="scenarioRows" empty="还没有调用记录（重启后归零）"></DataTable>
    </section>

    <section v-if="lastErrors.length" class="glass panel">
      <div class="panel-head"><h2>最近异常</h2><span class="hint">只记录最近一次，用于快速定位</span></div>
      <InfoGrid :rows="lastErrors"></InfoGrid>
    </section>

    <section class="glass panel">
      <div class="panel-head"><h2>原始指标</h2><span class="hint">进程内累计，重启后归零</span></div>
      <p v-if="error" class="hint" style="color: var(--bad-ink)">{{ error }}</p>
      <JsonBlock title="展开运行期指标" :data="data"></JsonBlock>
    </section>
  </div>
</template>
