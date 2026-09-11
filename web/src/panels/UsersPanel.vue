<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import { fmtNum, fmtTime, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const PAGE_SIZE = 50
const users = ref([])
const error = ref('')
const detail = ref(null)
const detailUser = ref('')
const detailPage = ref(0)
const detailLoading = ref(false)

const SECTION_LABELS = {
  profile: '资料与人设',
  conversations: '对话证据',
  coreMemories: '核心记忆',
  workMemories: '工作记忆',
  episodicMemories: '情景记忆',
  media: '媒体文件',
  reminders: '提醒任务'
}

const columns = [
  { label: '用户', key: 'displayUserId', mono: true },
  { label: '最近活动', value: row => fmtTime(row.lastSeenAt) },
  { label: '通道', value: row => zh('channel', row.channel) },
  { label: '消息', value: row => fmtNum(row.messageCount) },
  { label: '记忆', value: row => fmtNum(row.memoryCount) },
  { label: '提醒', value: row => fmtNum(row.reminderCount) }
]

const sections = computed(() => {
  if (!detail.value) return []
  return Object.entries(detail.value)
    .filter(([key]) => !['page', 'pageSize', 'truncated', 'userId'].includes(key))
    .map(([key, value]) => ({
      key,
      title: (SECTION_LABELS[key] || key) + (Array.isArray(value) ? '（' + value.length + ' 条）' : ''),
      body: JSON.stringify(value, null, 2)
    }))
})

async function loadUsers() {
  try {
    users.value = await api('/users')
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  }
}

async function openDetail(userId) {
  detailUser.value = userId
  detailPage.value = 0
  await loadDetail()
}

async function loadDetail() {
  detailLoading.value = true
  try {
    detail.value = await api('/users/' + encodeURIComponent(detailUser.value)
      + '?page=' + detailPage.value + '&size=' + PAGE_SIZE)
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  } finally {
    detailLoading.value = false
  }
}

watch(() => props.tick, () => loadUsers())
onMounted(() => loadUsers())
</script>

<template>
  <div class="stack">
    <section class="glass panel">
      <div class="panel-head"><h2>用户</h2><span class="hint">用户标识已脱敏；点开可看记忆、对话与提醒</span></div>
      <p v-if="error" class="hint" style="color: #ffb0bc">{{ error }}</p>
      <DataTable :columns="columns" :rows="users">
        <template #actions="{ row }">
          <button class="btn-link" @click="openDetail(row.userId)">查看详情</button>
        </template>
      </DataTable>
    </section>

    <section v-if="detail" class="glass panel">
      <div class="panel-head">
        <h2>用户详情 · {{ detail.userId }}</h2>
        <span class="hint">{{ detailLoading ? '加载中…' : '第 ' + (detailPage + 1) + ' 页，每页 ' + PAGE_SIZE + ' 条' }}</span>
      </div>

      <details v-for="section in sections" :key="section.key" class="item">
        <summary>{{ section.title }}</summary>
        <pre class="json">{{ section.body }}</pre>
      </details>

      <div class="pager">
        <button class="btn btn-sm" :disabled="detailPage === 0 || detailLoading"
                @click="detailPage -= 1; loadDetail()">上一页</button>
        <button class="btn btn-sm" :disabled="!detail.truncated || detailLoading"
                @click="detailPage += 1; loadDetail()">下一页</button>
      </div>
    </section>
  </div>
</template>
