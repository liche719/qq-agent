<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import { fmtTime, taskTone, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})
const emit = defineEmits(['refresh'])

const PAGE_SIZE = 20
const status = ref('')
const taskType = ref('')
const failureReason = ref('')
const query = ref('')
const page = ref(0)
const total = ref(0)
const items = ref([])
const error = ref('')
const busy = ref(false)

const columns = [
  { label: '任务', key: 'taskId', mono: true },
  { label: '状态', tag: row => ({ text: zh('task', row.status), tone: taskTone(String(row.status)) }) },
  { label: '类型', key: 'taskType' },
  { label: '用户', key: 'userId', mono: true },
  { label: '失败原因', key: 'failureReason', wide: true }
]

async function load(reset = false) {
  if (reset) page.value = 0
  busy.value = true
  error.value = ''
  try {
    const data = await api('/tasks?status=' + encodeURIComponent(status.value)
      + '&taskType=' + encodeURIComponent(taskType.value)
      + '&failureReason=' + encodeURIComponent(failureReason.value)
      + '&query=' + encodeURIComponent(query.value)
      + '&page=' + page.value + '&size=' + PAGE_SIZE)
    items.value = data.items || []
    total.value = Number(data.total) || 0
  } catch (caught) {
    error.value = caught.message
  } finally {
    busy.value = false
  }
}

async function retry(row) {
  if (!window.confirm('确认安全重试该任务？')) return
  try {
    const result = await api('/actions/tasks/' + encodeURIComponent(row.taskId) + '/retry', { method: 'POST' })
    if (!result.accepted) throw new Error('任务不满足安全重试条件或已被领取')
    await load()
    emit('refresh')
  } catch (caught) {
    error.value = caught.message
  }
}

watch(() => props.tick, () => load())
onMounted(() => load())

defineExpose({ load })
</script>

<template>
  <section class="glass panel">
    <div class="panel-head">
      <h2>任务列表</h2>
      <span class="hint">
        共 {{ total }} 条，当前显示第 {{ total === 0 ? 0 : page * PAGE_SIZE + 1 }} 至
        {{ Math.min(total, (page + 1) * PAGE_SIZE) }} 条
      </span>
    </div>

    <div class="toolbar">
      <select v-model="status" aria-label="任务状态">
        <option value="">全部状态</option>
        <option value="RUNNING">运行中</option>
        <option value="FAILED">失败</option>
        <option value="UNKNOWN_RESULT">结果未知</option>
        <option value="REPLY_SENT">已回复</option>
      </select>
      <input v-model="taskType" placeholder="任务类型" aria-label="任务类型">
      <input v-model="failureReason" placeholder="失败原因包含" aria-label="失败原因">
      <input v-model="query" placeholder="关键词" aria-label="关键词">
      <button class="btn" :disabled="busy" @click="load(true)">筛选</button>
    </div>

    <p v-if="error" class="hint" style="color: #ffb0bc">{{ error }}</p>

    <DataTable :columns="columns" :rows="items">
      <template #actions="{ row }">
        <button v-if="row.status === 'UNKNOWN_RESULT' && String(row.replaySafe) === 'true'"
                class="btn-link" @click="retry(row)">安全重试</button>
      </template>
    </DataTable>

    <div class="pager">
      <button class="btn btn-sm" :disabled="page === 0 || busy" @click="page -= 1; load()">上一页</button>
      <button class="btn btn-sm" :disabled="(page + 1) * PAGE_SIZE >= total || busy"
              @click="page += 1; load()">下一页</button>
      <span v-if="total === 0" class="info">没有符合条件的任务</span>
    </div>
  </section>
</template>
