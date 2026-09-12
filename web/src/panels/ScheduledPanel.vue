<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import MarkdownText from '../components/MarkdownText.vue'
import { zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const data = ref(null)
const error = ref('')
const busy = ref('')
const message = ref('')
const form = ref({ title: '', instruction: '', cron: '0 0 8 * * ?' })

const tasks = computed(() => data.value?.tasks || [])
const builtin = computed(() => data.value?.builtin || [])
const owner = computed(() => data.value?.ownerUserId || '')

const taskColumns = [
  { label: '标题', key: 'title' },
  { label: '执行内容', key: 'instruction', wide: true },
  { label: '频率', key: 'schedule' },
  { label: '下次执行', key: 'nextRunAt' },
  { label: '上次执行', key: 'lastRunAt' },
  { label: '状态', tag: row => ({ text: statusText(row), tone: statusTone(row) }) },
  { label: '已执行', key: 'runCount' }
]

const builtinColumns = [
  { label: '名称', key: 'name' },
  { label: '类型', key: 'group' },
  { label: '说明', key: 'description', wide: true },
  { label: '频率', key: 'schedule' },
  { label: '下次执行', key: 'nextRunAt' },
  { label: '备注', key: 'note', wide: true }
]

function statusText(row) {
  if (!row.enabled) return '已暂停'
  return zh('task', row.status) === '结果未知' ? '未执行' : zh('task', row.status)
}

function statusTone(row) {
  if (!row.enabled) return 'warn'
  if (row.status === 'SUCCESS') return 'ok'
  if (row.status === 'FAILED') return 'bad'
  return 'warn'
}

async function load() {
  try {
    data.value = await api('/scheduled/overview')
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  }
}

async function run(action, path, options = {}, done = '') {
  busy.value = action
  message.value = ''
  try {
    const result = await api(path, options)
    if (result.accepted === false) throw new Error(result.message || '操作未被接受')
    message.value = result.message || done || '操作已执行'
    await load()
  } catch (caught) {
    message.value = '操作失败：' + caught.message
  } finally {
    busy.value = ''
  }
}

function create() {
  if (!form.value.instruction.trim()) {
    message.value = '先写清楚到点要做什么'
    return
  }
  run('create', '/scheduled', {
    method: 'POST',
    body: { title: form.value.title.trim(), instruction: form.value.instruction.trim(), cron: form.value.cron.trim() }
  }, '定时任务已创建').then(() => {
    if (!message.value.startsWith('操作失败')) form.value = { title: '', instruction: '', cron: '0 0 8 * * ?' }
  })
}

function toggle(row) {
  run('toggle-' + row.id, '/scheduled/' + row.id + '/toggle', { method: 'POST', body: { enabled: !row.enabled } })
}

function runNow(row) {
  run('run-' + row.id, '/scheduled/' + row.id + '/run', { method: 'POST' }, '已让它在后台执行，结果会发到 QQ')
}

function remove(row) {
  if (!window.confirm('删除定时任务「' + row.title + '」？')) return
  run('del-' + row.id, '/scheduled/' + row.id, { method: 'DELETE' }, '已删除')
}

watch(() => props.tick, () => load())
onMounted(() => load())
</script>

<template>
  <div class="stack">
    <section class="glass panel">
      <div class="panel-head">
        <h2>我创建的定时任务</h2>
        <span class="hint">
          到点我会真的去做完这件事，再把结果发给你（不同于「定时提醒」只发一句话）
        </span>
      </div>

      <DataTable :columns="taskColumns" :rows="tasks" empty="还没有定时任务，可以在下面新建，或直接在 QQ 里说「每天早上 8 点把今天的天气发我」">
        <template #actions="{ row }">
          <button class="btn-link" :disabled="busy === 'run-' + row.id" @click="runNow(row)">立即执行</button>
          <button class="btn-link" :disabled="busy === 'toggle-' + row.id" @click="toggle(row)">
            {{ row.enabled ? '暂停' : '恢复' }}
          </button>
          <button class="btn-link" :disabled="busy === 'del-' + row.id" @click="remove(row)">删除</button>
        </template>
      </DataTable>

      <div v-if="tasks.some(t => t.lastResult || t.lastError)" class="stack" style="margin-top: 14px">
        <div v-for="row in tasks.filter(t => t.lastResult || t.lastError)" :key="'r-' + row.id" class="glass panel"
             style="padding: 12px 14px">
          <div class="hint" style="margin-bottom: 6px">
            #{{ row.id }}「{{ row.title }}」上次结果（{{ row.lastRunAt || '刚执行' }}）
          </div>
          <MarkdownText :text="row.lastResult || row.lastError"></MarkdownText>
        </div>
      </div>

      <div class="panel-head" style="margin-top: 18px"><h2>新建</h2>
        <span class="hint">归属用户：{{ owner || '未配置 alert.qq-openid' }}</span>
      </div>
      <div class="toolbar">
        <input v-model="form.title" placeholder="标题（如：早上天气）" aria-label="标题">
        <input v-model="form.cron" placeholder="Cron：0 0 8 * * ?" aria-label="Cron" style="max-width: 190px">
      </div>
      <div class="toolbar">
        <input v-model="form.instruction" placeholder="到点要执行的指令，例如：查一下我所在城市今天的天气，用一两句话告诉我" aria-label="执行指令">
        <button class="btn btn-sm" :disabled="busy === 'create'" @click="create">创建</button>
      </div>
      <p class="hint" style="margin-top: 8px">
        {{ data?.cronHint || 'Quartz 6 段：秒 分 时 日 月 周' }}
      </p>
      <p v-if="message" class="hint" style="margin-top: 8px">{{ message }}</p>
      <p v-if="error" class="hint" style="margin-top: 8px; color: var(--bad-ink)">{{ error }}</p>
    </section>

    <section class="glass panel">
      <div class="panel-head">
        <h2>系统内置的定时任务</h2>
        <span class="hint">机器人自己按计划在跑的东西；固定周期的任务 Spring 不暴露下次执行时间，标「—」</span>
      </div>
      <DataTable :columns="builtinColumns" :rows="builtin" empty="没有内置任务"></DataTable>
    </section>
  </div>
</template>
