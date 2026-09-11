<script setup>
import { computed } from 'vue'
import ChartBars from '../components/ChartBars.vue'
import InfoGrid from '../components/InfoGrid.vue'
import JsonBlock from '../components/JsonBlock.vue'
import { fmtBytes, fmtNum, fmtTime } from '../labels'

const props = defineProps({
  overview: { type: Object, default: null },
  history: { type: Array, default: () => [] }
})

const runtimeRows = computed(() => {
  const jvm = props.overview?.jvm || {}
  const used = Number(jvm.heapUsed) || 0
  const max = Number(jvm.heapMax) || 0
  const percent = max > 0 ? Math.round(used / max * 100) : -1
  const cpu = Number(jvm.systemCpuLoad)
  return [
    ['堆内存占用', fmtBytes(used) + (percent >= 0 ? ' / ' + fmtBytes(max) : '')],
    ['堆内存比例', percent >= 0 ? percent + '%' : '—'],
    ['线程数', fmtNum(jvm.threads)],
    ['处理器负载', Number.isFinite(cpu) && cpu >= 0 ? Math.round(cpu * 100) + '%' : '—'],
    ['可用处理器', fmtNum(jvm.processors)],
    ['磁盘可用', fmtBytes(jvm.diskFree) + ' / ' + fmtBytes(jvm.diskTotal)],
    ['服务时间', fmtTime(props.overview?.time)]
  ]
})

const countRows = computed(() => {
  const tasks = props.overview?.tasks || {}
  return [
    ['用户', fmtNum(props.overview?.users)],
    ['对话证据', fmtNum(props.overview?.conversations)],
    ['核心记忆', fmtNum(props.overview?.coreMemories)],
    ['工作记忆', fmtNum(props.overview?.workMemories)],
    ['情景记忆', fmtNum(props.overview?.episodes)],
    ['提醒任务', fmtNum(props.overview?.reminders)],
    ['任务·运行中', fmtNum(tasks.RUNNING)],
    ['任务·失败', fmtNum(tasks.FAILED)],
    ['任务·结果未知', fmtNum(tasks.UNKNOWN_RESULT)],
    ['任务·已回复', fmtNum(tasks.REPLY_SENT)]
  ]
})

const dependencies = computed(() => ({
  dependencies: props.overview?.dependencies || {},
  moduleErrors: props.overview?.moduleErrors || {}
}))
</script>

<template>
  <div class="stack">
    <div class="grid-2">
      <section class="glass panel">
        <div class="panel-head"><h2>运行时</h2><span class="hint">内存、线程与磁盘</span></div>
        <InfoGrid :rows="runtimeRows"></InfoGrid>
      </section>
      <section class="glass panel">
        <div class="panel-head"><h2>数据与任务</h2><span class="hint">持久化记录条数</span></div>
        <InfoGrid :rows="countRows"></InfoGrid>
      </section>
    </div>

    <section class="glass panel">
      <div class="panel-head"><h2>堆内存趋势</h2><span class="hint">服务端环形缓冲，重启后重新采样；每根柱是一次采样</span></div>
      <ChartBars :samples="history.slice(-60)"></ChartBars>
    </section>

    <section class="glass panel">
      <div class="panel-head"><h2>完整状态</h2><span class="hint">接口原始返回，排查问题时看这里</span></div>
      <JsonBlock title="展开完整状态" :data="overview"></JsonBlock>
      <JsonBlock title="展开依赖与数据源错误" :data="dependencies"></JsonBlock>
    </section>
  </div>
</template>
