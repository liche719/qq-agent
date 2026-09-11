<script setup>
import { computed, ref } from 'vue'
import { api } from '../api'
import InfoGrid from '../components/InfoGrid.vue'
import JsonBlock from '../components/JsonBlock.vue'
import { fmtMs, fmtNum, fmtTime, zh } from '../labels'

const props = defineProps({
  overview: { type: Object, default: null }
})
const emit = defineEmits(['refresh'])

const busy = ref('')
const message = ref('')

const metrics = computed(() => props.overview?.qqMetrics || {})

const rows = computed(() => {
  const m = metrics.value
  const byStatus = m.apiErrorsByStatus && Object.keys(m.apiErrorsByStatus).length
    ? Object.entries(m.apiErrorsByStatus).map(([code, count]) => code + ' 共 ' + count + ' 次').join('；')
    : '无'
  const list = [
    ['网关状态', zh('qq', props.overview?.qq)],
    ['文本发送成功', fmtNum(m.textSendSuccess)],
    ['文本发送失败', fmtNum(m.textSendFailure)],
    ['媒体发送成功', fmtNum(m.mediaSendSuccess)],
    ['媒体发送失败', fmtNum(m.mediaSendFailure)],
    ['接口错误总数', fmtNum(m.apiErrors)],
    ['重连成功 / 失败', fmtNum(m.reconnectSuccess) + ' / ' + fmtNum(m.reconnectFailure)],
    ['令牌刷新失败', fmtNum(m.tokenRefreshFailure)],
    ['心跳失败', fmtNum(m.heartbeatFailure)],
    ['网关帧解析失败', fmtNum(m.gatewayFrameParseFailure)],
    ['网关鉴权失败', fmtNum(m.gatewayIdentifyFailure)],
    ['网关恢复失败', fmtNum(m.gatewayResumeFailure)],
    ['引用查询成功 / 失败', fmtNum(m.quoteLookupSuccess) + ' / ' + fmtNum(m.quoteLookupFailure)],
    ['分片上传失败', fmtNum(m.chunkUploadFailure)],
    ['文本发送平均耗时', fmtMs(m.textSendAverageMs)],
    ['媒体发送平均耗时', fmtMs(m.mediaSendAverageMs)],
    ['连接建立于', fmtTime(m.connectedAt)],
    ['最近网关事件', fmtTime(m.lastGatewayEventAt)],
    ['接口错误按状态码', byStatus]
  ]
  if (m.lastApiError) list.push(['最近接口错误', String(m.lastApiError)])
  return list
})

async function run(action, path, confirmText) {
  if (confirmText && !window.confirm(confirmText)) return
  busy.value = action
  message.value = ''
  try {
    const result = await api(path, { method: 'POST' })
    if (result.accepted === false) throw new Error(result.message || '操作未被接受')
    message.value = result.message || '操作已执行'
    emit('refresh')
  } catch (error) {
    message.value = '操作失败：' + error.message
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <section class="glass panel">
    <div class="panel-head"><h2>QQ 通道</h2><span class="hint">网关连通性与收发、心跳计数</span></div>
    <InfoGrid :rows="rows"></InfoGrid>

    <div class="toolbar">
      <button class="btn btn-sm" :disabled="busy === 'reconnect'"
              @click="run('reconnect', '/actions/qq/reconnect', '确认触发 QQ 重连？')">触发 QQ 重连</button>
      <button class="btn btn-sm" :disabled="busy === 'cleanup'"
              @click="run('cleanup', '/actions/cache/cleanup', '确认清理过期缓存？')">清理过期缓存</button>
      <button class="btn btn-sm" :disabled="busy === 'alert'"
              @click="run('alert', '/actions/alerts/test')">发送测试告警</button>
      <span v-if="message" class="hint">{{ message }}</span>
    </div>

    <JsonBlock title="展开通道指标" :data="metrics"></JsonBlock>
  </section>
</template>
