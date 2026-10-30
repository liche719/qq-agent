<script setup>
/**
 * 自主模块开关（2026-10-24）——"它自己"那块（刷视频、写评论、反思）的运行时开关。
 *
 * 后端：GET/POST /api/admin/self/switch，值存 Redis（AOF），环境变量只当默认值。
 * 关掉后三处一起停：心跳/反思任务、提示词里的"它自己"、模型能看到的 self* 工具。
 */
import { onMounted, ref } from 'vue'
import { api } from '../api'

const enabled = ref(false)
const source = ref('env')
const busy = ref(false)
const message = ref('')

async function load() {
  try {
    const data = await api('/self/switch')
    enabled.value = !!data?.enabled
    source.value = data?.source || 'env'
  } catch (error) {
    message.value = error.message || '读取开关失败'
  }
}

async function toggle() {
  busy.value = true
  message.value = ''
  try {
    const data = await api('/self/switch', {
      method: 'POST',
      body: { enabled: !enabled.value }
    })
    enabled.value = !!data?.enabled
    source.value = data?.source || source.value
    message.value = data?.message || ''
  } catch (error) {
    message.value = error.message || '切换失败'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="glass panel">
    <div class="panel-head">
      <h2>自主模块</h2>
      <span class="hint">关掉后它不再自己刷视频、写评论、做反思</span>
    </div>
    <div class="row" style="margin-top: 12px">
      <button class="btn" :class="{ 'btn-primary': !enabled }" :disabled="busy" @click="toggle">
        {{ busy ? '切换中…' : (enabled ? '关闭自主模块' : '开启自主模块') }}
      </button>
      <span class="hint">当前：{{ enabled ? '运行中' : '已关闭' }}（{{ source === 'panel' ? '面板设置' : '环境变量默认' }}）</span>
    </div>
    <p v-if="message" class="hint" style="margin: 8px 0 0">{{ message }}</p>
  </section>
</template>
