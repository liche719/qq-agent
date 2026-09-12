<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import { fmtTime, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const level = ref('')
const query = ref('')
const entries = ref([])
const error = ref('')
const busy = ref(false)

/** 请求序号：自动刷新和手动筛选可能同时在飞，先发的后返回会覆盖后发的正确结果 */
let seq = 0

const columns = [
  { label: '时间', value: row => fmtTime(row.createdAt) },
  { label: '级别 / 操作', value: row => (row.level ? zh('level', row.level) : zh('action', row.action)) },
  { label: '用户', key: 'userId', mono: true },
  { label: '详情', value: row => row.detail || row.message, wide: true },
  { label: '来源', value: row => zh('source', row.source) }
]

async function load() {
  const my = ++seq
  busy.value = true
  try {
    const data = await api('/logs?level=' + encodeURIComponent(level.value) + '&query=' + encodeURIComponent(query.value))
    if (my !== seq) return
    entries.value = data || []
    error.value = ''
  } catch (caught) {
    if (my !== seq) return
    error.value = caught.message
  } finally {
    busy.value = false
  }
}

watch(() => props.tick, () => load())
onMounted(() => load())
</script>

<template>
  <section class="glass panel">
    <div class="panel-head"><h2>运行日志</h2><span class="hint">操作审计（最近 100 条）与应用日志尾部（200 行）</span></div>

    <div class="toolbar">
      <select v-model="level" aria-label="日志级别">
        <option value="">全部级别</option>
        <option value="INFO">信息</option>
        <option value="WARN">警告</option>
        <option value="ERROR">错误</option>
      </select>
      <input v-model="query" placeholder="关键词过滤" aria-label="关键词">
      <button class="btn" :disabled="busy" @click="load">筛选</button>
    </div>

    <p v-if="error" class="hint" style="color: var(--bad-ink)">{{ error }}</p>
    <DataTable :columns="columns" :rows="entries"></DataTable>
  </section>
</template>
