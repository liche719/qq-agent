<script setup>
import { fmtBytes, fmtTime } from '../labels'

const props = defineProps({
  samples: { type: Array, default: () => [] }
})

function height(sample) {
  const peak = Math.max(1, ...props.samples.map(item => Number(item.jvm?.heapUsed) || 0))
  return Math.max(3, (Number(sample.jvm?.heapUsed) || 0) / peak * 100) + '%'
}

function tip(sample) {
  return fmtTime(sample.at) + ' · ' + fmtBytes(sample.jvm?.heapUsed)
}
</script>

<template>
  <div class="chart">
    <div class="chart-bars">
      <i v-for="(sample, index) in samples" :key="index" class="chart-bar"
         :style="{ height: height(sample) }" :title="tip(sample)"></i>
    </div>
  </div>
  <div class="chart-axis">
    <span>{{ samples.length ? fmtTime(samples[0].at) : '—' }}</span>
    <span>峰值 {{ samples.length ? fmtBytes(Math.max(...samples.map(item => Number(item.jvm?.heapUsed) || 0))) : '—' }}</span>
    <span>{{ samples.length ? fmtTime(samples[samples.length - 1].at) : '—' }}</span>
  </div>
</template>
