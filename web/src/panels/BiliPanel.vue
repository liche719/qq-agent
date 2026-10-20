<script setup>
/**
 * B站 页签（2026-10-20 加）：扫码登录 + 状态。
 *
 * 为什么要有这个页签：B站凭据原来只能手工复制 Cookie 到服务器 .env，而且那份**只有 SESSDATA**，
 * 缺 `bili_jct` 就发不了评论。扫码一次能同时拿到两者，所以做成点一下、扫一下就完事。
 *
 * 轮询是前端驱动的（每 2 秒一次），不做后端定时器：页面关掉就停，不会在服务器上留孤儿任务。
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '../api'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const status = ref(null)
const error = ref('')
const loading = ref(false)
const qr = ref(null)          // { key, image, url, expiresInSeconds }
const pollText = ref('')
const pollState = ref('')
const done = ref(null)        // 登录成功后的用户信息
let timer = null
let expiresAt = 0

const readable = computed(() => Boolean(status.value?.readable))
const writable = computed(() => Boolean(status.value?.writable))
const source = computed(() => status.value?.source || '未知')

async function loadStatus() {
  loading.value = true
  error.value = ''
  try {
    status.value = await api('/bilibili/status')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function startLogin() {
  stopPolling()
  error.value = ''
  done.value = null
  pollText.value = '正在向 B 站要二维码…'
  pollState.value = ''
  try {
    qr.value = await api('/bilibili/login/qrcode', { method: 'POST' })
    expiresAt = Date.now() + (qr.value.expiresInSeconds || 180) * 1000
    pollText.value = '用 B 站 App 扫上面这张二维码'
    timer = setInterval(poll, 2000)
  } catch (e) {
    error.value = e.message
    pollText.value = ''
    qr.value = null
  }
}

async function poll() {
  if (!qr.value) return stopPolling()
  if (Date.now() > expiresAt) {
    stopPolling()
    pollState.value = 'expired'
    pollText.value = '二维码过期了，重新点一次「生成二维码」'
    return
  }
  try {
    const result = await api('/bilibili/login/poll?key=' + encodeURIComponent(qr.value.key))
    pollState.value = result.state
    pollText.value = result.text || ''
    if (result.state === 'confirmed') {
      stopPolling()
      done.value = result
      qr.value = null
      await loadStatus()
    } else if (result.state === 'expired' || result.state === 'error') {
      stopPolling()
    }
  } catch (e) {
    // 网络抖一下就等下一轮，不打断
    pollText.value = '查询状态失败，重试中…'
  }
}

async function logout() {
  stopPolling()
  if (!window.confirm('清除扫码登录的凭据？（服务器 .env 里的兜底不动）')) return
  error.value = ''
  try {
    status.value = await api('/bilibili/credential', { method: 'DELETE' })
    done.value = null
    qr.value = null
    pollText.value = ''
    pollState.value = ''
  } catch (e) {
    error.value = e.message
  }
}

onBeforeUnmount(stopPolling)
loadStatus()
</script>

<template>
  <section class="panel">
    <header class="panel-head">
      <h2>B站</h2>
      <p class="hint">扫码登录一次，凭据存库里；读字幕/关注动态只要有 SESSDATA 就够，<b>发评论/回复还需要 bili_jct</b>，扫码会一起拿到。</p>
    </header>

    <div v-if="error" class="banner banner-bad">{{ error }}</div>

    <div class="card">
      <div class="row">
        <span class="label">当前凭据</span>
        <span class="value">{{ source }}</span>
      </div>
      <div class="row">
        <span class="label">能不能读</span>
        <span class="value">{{ readable ? '可以（字幕、关注动态、稍后再看）' : '没有凭据' }}</span>
      </div>
      <div class="row">
        <span class="label">能不能发评论</span>
        <span class="value">
          <b :class="writable ? 'ok' : 'warn'">{{ writable ? '可以' : '不行（缺 bili_jct）' }}</b>
        </span>
      </div>
      <div class="row" v-if="status?.uname">
        <span class="label">账号</span>
        <span class="value">{{ status.uname }}<span v-if="status.mid">（UID {{ status.mid }}）</span></span>
      </div>
      <div class="row" v-if="status?.updatedAt">
        <span class="label">凭据更新时间</span>
        <span class="value">{{ status.updatedAt.replace('T', ' ').slice(0, 19) }}</span>
      </div>

      <div class="actions">
        <button class="btn" :disabled="loading" @click="startLogin">
          {{ qr ? '换一张二维码' : '生成二维码 / 重新登录' }}
        </button>
        <button class="btn ghost" :disabled="!readable" @click="logout">退出登录</button>
      </div>
    </div>

    <div v-if="qr" class="card qr-card">
      <img :src="qr.image" alt="B站登录二维码" class="qr" />
      <p class="qr-hint">用 <b>B 站手机客户端</b>扫这张码，扫完在手机上点确认。</p>
      <p class="qr-state">{{ pollText }}</p>
      <p v-if="pollState === 'scanned'" class="qr-state warn">已扫码，等你在手机上点确认…</p>
    </div>

    <div v-if="done" class="banner banner-ok">
      登录成功{{ done.uname ? '：' + done.uname : '' }}，凭据已保存。
      {{ done.writable ? '现在可以发评论了。' : '但没拿到写权限（bili_jct 为空），只能读。' }}
    </div>
  </section>
</template>

<style scoped>
.panel { display: flex; flex-direction: column; gap: 14px; }
.panel-head h2 { margin: 0 0 6px; }
.hint { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 16px; }
.row { display: flex; gap: 12px; padding: 6px 0; border-bottom: 1px dashed var(--border); }
.row:last-of-type { border-bottom: none; }
.label { width: 130px; color: var(--muted); font-size: 13px; flex: none; }
.value { font-size: 14px; }
.ok { color: var(--ok, #2e7d32); }
.warn { color: var(--warn, #b26a00); }
.actions { display: flex; gap: 10px; margin-top: 14px; }
.btn { padding: 8px 16px; border-radius: 8px; border: 1px solid var(--border); background: var(--accent); color: #fff; cursor: pointer; font-size: 14px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn.ghost { background: transparent; color: var(--text); }
.qr-card { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.qr { width: 260px; height: 260px; image-rendering: pixelated; background: #fff; padding: 8px; border-radius: 8px; }
.qr-hint { margin: 0; font-size: 13px; color: var(--muted); }
.qr-state { margin: 0; font-size: 14px; }
.banner { padding: 10px 14px; border-radius: 10px; font-size: 14px; }
.banner-bad { background: rgba(200, 60, 60, .12); color: #b3261e; }
.banner-ok { background: rgba(40, 140, 80, .12); color: #1b5e20; }
</style>

