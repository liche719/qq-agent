<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import InfoGrid from '../components/InfoGrid.vue'
import JsonBlock from '../components/JsonBlock.vue'
import { fmtMs, fmtNum } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const data = ref(null)
const error = ref('')

function percent(value) {
  return value === null || value === undefined ? '—' : value + '%'
}

function ms(value) {
  return value === null || value === undefined ? '—' : fmtMs(value)
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

watch(() => props.tick, () => load())
onMounted(() => load())
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
