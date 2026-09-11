<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import { fmtNum, fmtTime, zh } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const PAGE_SIZE = 50
const SECTIONS = [
  { key: 'chat', label: '聊天记录' },
  { key: 'memory', label: '长期记忆' },
  { key: 'reminders', label: '提醒任务' }
]

const users = ref([])
const error = ref('')
const section = ref('chat')
const showSystem = ref(false)

const current = ref(null)
const messages = ref([])
const loadedPages = ref(0)
const hasMore = ref(false)
const loadingChat = ref(false)
const chatBox = ref(null)

const detail = ref(null)
const loadingDetail = ref(false)

/** 后端可能把空的 lastSeenAt 序列化成字符串 "null"，只认真正的日期，其余排到最后 */
const activity = row => {
  const value = String(row.lastSeenAt || '')
  return /^\d{4}-\d{2}-\d{2}/.test(value) ? value : ''
}

const sortedUsers = computed(() => [...users.value].sort((left, right) =>
  activity(right).localeCompare(activity(left))))

const columns = [
  { label: '用户', key: 'displayUserId', mono: true },
  { label: '最近活动', value: row => fmtTime(row.lastSeenAt) },
  { label: '通道', value: row => zh('channel', row.channel) },
  { label: '消息', value: row => fmtNum(row.messageCount) },
  { label: '记忆', value: row => fmtNum(row.memoryCount) },
  { label: '提醒', value: row => fmtNum(row.reminderCount) }
]

const visibleMessages = computed(() =>
  messages.value.filter(message => showSystem.value || message.role !== 'system'))

const memorySections = computed(() => {
  if (!detail.value) return []
  const labels = {
    profile: '资料与人设', coreMemories: '核心记忆', workMemories: '工作记忆',
    episodicMemories: '情景记忆', media: '媒体文件'
  }
  return Object.entries(detail.value)
    .filter(([key]) => Object.prototype.hasOwnProperty.call(labels, key))
    .map(([key, value]) => ({
      key,
      title: labels[key] + (Array.isArray(value) ? '（' + value.length + ' 条）' : ''),
      body: JSON.stringify(value, null, 2)
    }))
})

const reminders = computed(() => {
  const items = detail.value?.reminders
  if (!Array.isArray(items) || !items.length) return ''
  return items.map(item => '· ' + fmtTime(item.triggerAt) + '  ' + (item.content || '')
    + (item.cron ? '（重复：' + item.cron + '）' : '') + '  [' + (item.status || '') + ']').join('\n')
})

async function loadUsers() {
  try {
    users.value = await api('/users')
    error.value = ''
  } catch (caught) {
    error.value = caught.message
  }
}

async function openUser(user) {
  current.value = user
  section.value = 'chat'
  messages.value = []
  loadedPages.value = 0
  hasMore.value = true
  detail.value = null
  await loadChatPage(0)
}

/** 接口按时间倒序返回，这里翻成正序；往后翻页拿到的是更早的消息，插到前面 */
async function loadChatPage(page) {
  if (!current.value || loadingChat.value) return
  loadingChat.value = true
  try {
    const data = await api('/users/' + encodeURIComponent(current.value.userId)
      + '?page=' + page + '&size=' + PAGE_SIZE)
    const batch = (data.conversations || []).slice().reverse()
    messages.value = page === 0 ? batch : batch.concat(messages.value)
    loadedPages.value = page + 1
    hasMore.value = (data.conversations || []).length === PAGE_SIZE
    detail.value = data
    error.value = ''
    if (page === 0) {
      await nextTick()
      scrollToBottom()
    }
  } catch (caught) {
    error.value = caught.message
  } finally {
    loadingChat.value = false
  }
}

function scrollToBottom() {
  const box = chatBox.value
  if (box) box.scrollTop = box.scrollHeight
}

function loadEarlier() {
  loadChatPage(loadedPages.value)
}

watch(() => props.tick, () => loadUsers())
onMounted(() => loadUsers())
</script>

<template>
  <div class="stack">
    <section class="glass panel">
      <div class="panel-head">
        <h2>用户</h2>
        <span class="hint">共 {{ users.length }} 个用户，每个用户一个入口；点开可查看全部聊天记录</span>
      </div>
      <p v-if="error" class="hint" style="color: var(--bad-ink)">{{ error }}</p>
      <DataTable :columns="columns" :rows="sortedUsers">
        <template #actions="{ row }">
          <button class="btn-link" @click="openUser(row)">查看记录</button>
        </template>
      </DataTable>
    </section>

    <section v-if="current" class="glass panel">
      <div class="panel-head">
        <h2>{{ current.displayUserId }}</h2>
        <span class="hint">
          通道 {{ zh('channel', current.channel) }} · 消息 {{ fmtNum(current.messageCount) }}
          · 最近活动 {{ fmtTime(current.lastSeenAt) }}
        </span>
      </div>

      <div class="toolbar">
        <div class="segment">
          <button v-for="item in SECTIONS" :key="item.key" :class="{ active: section === item.key }"
                  @click="section = item.key">{{ item.label }}</button>
        </div>
        <label v-if="section === 'chat'" class="check" style="margin: 0">
          <input v-model="showSystem" type="checkbox">
          <span>显示工具调用</span>
        </label>
      </div>

      <template v-if="section === 'chat'">
        <div ref="chatBox" class="chat">
          <div class="chat-inner">
            <div v-if="hasMore" class="chat-hint">
              <button class="btn btn-sm" :disabled="loadingChat" @click="loadEarlier">
                {{ loadingChat ? '加载中…' : '加载更早的消息' }}
              </button>
            </div>
            <div v-else class="chat-hint">已经是最早的消息</div>

            <div v-for="(message, index) in visibleMessages" :key="message.id || index"
                 class="bubble" :class="message.role">
              <div class="text">{{ message.content }}</div>
              <div class="meta">
                {{ message.role === 'user' ? '用户' : message.role === 'assistant' ? '机器人' : '工具' }}
                · {{ fmtTime(message.createdAt) }}
              </div>
            </div>
            <div v-if="!visibleMessages.length" class="chat-hint">暂无会话记录</div>
          </div>
        </div>
      </template>

      <template v-else-if="section === 'memory'">
        <details v-for="item in memorySections" :key="item.key" class="item">
          <summary>{{ item.title }}</summary>
          <pre class="json">{{ item.body }}</pre>
        </details>
        <div v-if="!memorySections.length" class="empty">暂无记忆数据</div>
      </template>

      <template v-else>
        <pre v-if="reminders" class="json" style="margin-top: 14px">{{ reminders }}</pre>
        <div v-else class="empty">该用户没有提醒任务</div>
      </template>
    </section>
  </div>
</template>
