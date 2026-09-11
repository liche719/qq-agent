<script setup>
defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  empty: { type: String, default: '暂无数据' }
})

function text(row, column) {
  const value = column.value ? column.value(row) : row[column.key]
  return value === undefined || value === null || value === '' ? '—' : value
}
</script>

<template>
  <div class="table-wrap">
    <div v-if="!rows.length" class="empty">{{ empty }}</div>
    <table v-else>
      <thead>
        <tr>
          <th v-for="column in columns" :key="column.label">{{ column.label }}</th>
          <th v-if="$slots.actions">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="row.taskId || row.userId || row.id || index">
          <td v-for="column in columns" :key="column.label" :data-label="column.label"
              :class="{ mono: column.mono, wide: column.wide }">
            <span v-if="column.tag" class="tag" :class="column.tag(row).tone">{{ column.tag(row).text }}</span>
            <template v-else>{{ text(row, column) }}</template>
          </td>
          <td v-if="$slots.actions" data-label="操作">
            <slot name="actions" :row="row"></slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
