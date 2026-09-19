<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import MarkdownText from '../components/MarkdownText.vue'
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

/** 请求序号：上一次请求还没回来时用户可能已经点了别的用户，靠它丢弃过期响应 */
let chatSeq = 0

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
  const source = detail.value
  const sections = []

  const profile = source.profile
  if (profile) {
    sections.push({
      key: 'profile',
      title: '资料与人设',
      note: '记忆开关 ' + (profile.memoryEnabled ? '已开启' : '已关闭')
        + ' · 通道 ' + zh('channel', profile.lastChannel)
        + ' · 最近活动 ' + fmtTime(profile.lastSeenAt),
      entries: [{
        meta: '人设',
        text: profile.persona || '（未自定义人设，使用默认）',
        metaRight: '创建于 ' + fmtTime(profile.createdAt)
      }],
      raw: profile
    })
  }

  const pushList = (key, title, items, mapEntry) => {
    if (!Array.isArray(items) || !items.length) return
    sections.push({
      key,
      title: title + '（' + items.length + ' 条）',
      note: '',
      entries: items.map(mapEntry),
      raw: items
    })
  }

  // 2026-09-18：记忆三张表合并成一张（memory），后端只返回一个 memories 数组，按 kind 分组展示
  const memories = Array.isArray(source.memories) ? source.memories : []
  const byKind = (kind) => memories.filter(item => item.kind === kind)
  pushList('profileMemories', '长期设定', byKind('PROFILE'), item => ({
    meta: '重要度 ' + (item.importance ?? '—') + ' · ' + zh('memory', item.status),
    text: item.content,
    metaRight: fmtTime(item.updatedAt)
  }))
  pushList('taskMemories', '中期事项', byKind('TASK'), item => ({
    meta: '优先级 ' + (item.priority ?? '—') + (item.validUntil ? ' · 有效至 ' + fmtTime(item.validUntil) : ''),
    text: item.content,
    metaRight: fmtTime(item.updatedAt)
  }))
  pushList('experienceMemories', '经历', byKind('EXPERIENCE'), item => ({
    meta: (item.title || '经历') + (item.status ? ' · ' + zh('memory', item.status) : ''),
    text: item.content,
    metaRight: fmtTime(item.occurredAt)
  }))
  pushList('media', '媒体文件', source.media, item => ({
    meta: item.fileName || '文件',
    text: item.summary || '（无摘要）',
    metaRight: fmtTime(item.createdAt)
  }))
  return sections
})

const reminders = computed(() => {
  const items = detail.value?.reminders
  if (!Array.isArray(items) || !items.length) return ''
  return items.map(item => '· ' + fmtTime(item.triggerAt) + '  ' + (item.content || '')
    + (item.cron ? '（重复：' + item.cron + '）' : '') + '  [' + zh('task', item.status) + ']').join('\n')
})

async function loadUsers() {
  try {
    users.value = (await api('/users')) || []
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

function nearBottom(box) {
  return !box || box.scrollHeight - box.scrollTop - box.clientHeight < 48
}

/**
 * 接口按时间倒序返回，这里翻成正序；往后翻页拿到的是更早的消息，插到前面。
 * merge=true 是自动刷新用的：保留已经翻出来的更早消息，只把新出现的追加到末尾，
 * 并且不打断当前滚动位置（正在翻旧消息时不要把人拽到底部）。
 */
async function loadChatPage(page, merge = false) {
  if (!current.value) return
  const userId = current.value.userId
  const seq = ++chatSeq
  loadingChat.value = true
  const box = chatBox.value
  const stick = nearBottom(box)
  const previousTop = box ? box.scrollTop : 0
  try {
    const data = await api('/users/' + encodeURIComponent(userId)
      + '?page=' + page + '&size=' + PAGE_SIZE)
    // 期间可能已经切到别的用户、或者又发了一次请求，过期响应一律丢弃，不能写进当前状态
    if (seq !== chatSeq) return
    if (!current.value || current.value.userId !== userId) return
    const batch = ((data && data.conversations) || []).slice().reverse()
    if (merge && page === 0 && loadedPages.value > 1) {
      const known = new Set(messages.value.map(message => message.id))
      const fresh = batch.filter(message => message.id === undefined || !known.has(message.id))
      if (fresh.length) messages.value = messages.value.concat(fresh)
    } else {
      messages.value = page === 0 ? batch : batch.concat(messages.value)
      loadedPages.value = page + 1
      hasMore.value = batch.length === PAGE_SIZE
    }
    // 记忆与提醒也在同一个响应里，跟着一起刷新
    detail.value = data
    error.value = ''
    if (page === 0) {
      await nextTick()
      if (merge && !stick && box) box.scrollTop = previousTop
      else scrollToBottom()
    }
  } catch (caught) {
    if (seq !== chatSeq) return
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

/** 自动刷新：用户列表 + 当前打开用户的记录（聊天、长期记忆、提醒任务） */
function refreshCurrent() {
  loadUsers()
  if (current.value) loadChatPage(0, true)
}

watch(() => props.tick, refreshCurrent)
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
              <MarkdownText v-if="message.role !== 'system'" class="text" :text="message.content"></MarkdownText>
              <div v-else class="text">{{ message.content }}</div>
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
        <details v-for="item in memorySections" :key="item.key" class="item" open>
          <summary>{{ item.title }}</summary>
          <div class="memory-list">
            <div v-for="(entry, index) in item.entries" :key="index" class="memory-card">
              <div class="memory-head">
                <span class="memory-meta">{{ entry.meta }}</span>
                <span class="memory-time">{{ entry.metaRight }}</span>
              </div>
              <MarkdownText :text="entry.text"></MarkdownText>
            </div>
          </div>
          <details class="item" style="margin: 10px 14px 14px">
            <summary>原始数据</summary>
            <pre class="json">{{ JSON.stringify(item.raw, null, 2) }}</pre>
          </details>
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
