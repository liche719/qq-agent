<script setup>
/**
 * 工具（2026-10-26，P2-4）—— 一个地方看**64 个工具的真实使用情况**。
 *
 * 数据来自 `GET /api/admin/tools/stats?days=N`：调用次数、失败率、平均耗时，
 * 以及**从来没被调用过的工具**（64 个里有一大堆，是最值得清理的线索）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import InfoGrid from '../components/InfoGrid.vue'
import { fmtNum } from '../labels'

const props = defineProps({
  tick: { type: Number, default: 0 }
})

const RANGES = [
  { days: 1, label: '今天' },
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' }
]

const days = ref(7)
const data = ref(null)
const error = ref('')

const summary = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { label: '工具总数（被用过）', value: d.distinctTools ?? 0 },
    { label: '调用次数', value: fmtNum(d.totalCalls) },
    { label: '失败次数', value: fmtNum(d.totalFailed) },
    { label: '从未调用', value: (d.neverUsed || []).length }
  ]
})

const columns = [
  { key: 'tool', label: '工具' },
  { key: 'calls', label: '调用' },
  { key: 'failed', label: '失败' },
  { key: 'successRate', label: '成功率' },
  { key: 'avgMs', label: '平均耗时' }
]

const rows = computed(() => (data.value?.rows || []).map(r => ({
  ...r,
  avgMs: r.avgMs ? `${r.avgMs} ms` : '—'
})))

async function load() {
  error.value = ''
  try {
    data.value = await api(`/tools/stats?days=${days.value}`)
  } catch (e) {
    error.value = e?.message || '加载失败'
  }
}

onMounted(load)
watch(() => [days.value, props.tick], load)
</script>

<template>
  <div class="panel">
    <div class="row">
      <button v-for="r in RANGES" :key="r.days" class="chip" :class="{ on: days === r.days }"
              @click="days = r.days">{{ r.label }}</button>
      <span v-if="error" class="err">{{ error }}</span>
    </div>

    <InfoGrid :rows="summary" />

    <h3>调用与失败</h3>
    <DataTable :columns="columns" :rows="rows" />

    <h3 v-if="data?.neverUsed?.length">从未被调用（可以清掉或降级成按需解锁）</h3>
    <p class="muted" v-if="data?.neverUsed?.length">{{ data.neverUsed.join('、') }}</p>
  </div>
</template>

<style scoped>
.row { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.chip { border: 1px solid #d7dde6; background: #fff; border-radius: 999px; padding: 4px 12px; cursor: pointer; }
.chip.on { background: #1f4e79; color: #fff; border-color: #1f4e79; }
.err { color: #b42318; }
.muted { color: #5a6675; line-height: 1.7; }
h3 { margin: 18px 0 8px; font-size: 14px; }
</style>
