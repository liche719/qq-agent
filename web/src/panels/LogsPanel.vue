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

const columns = [
  { label: '时间', value: row => fmtTime(row.createdAt) },
  { label: '级别 / 操作', value: row => (row.level ? zh('level', row.level) : zh('action', row.action)) },
  { label: '用户', key: 'userId', mono: true },
  { label: '详情', value: row => row.detail || row.message, wide: true },
  { label: '来源', value: row => zh('source', row.source) }
]

async function load() {
  busy.value = true
  try {
    entries.value = await api('/logs?level=' + encodeURIComponent(level.value) + '&query=' + encodeURIComponent(query.value))
    error.value = ''
  } catch (caught) {
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
