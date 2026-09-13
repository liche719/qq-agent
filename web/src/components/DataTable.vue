<script setup>
/**
 * 通用数据表。两套用法互不影响：
 *   ① 手写面板：:columns/:rows/:empty + #actions 插槽（插槽内容原样渲染，语义不变）；
 *   ② 描述式面板：额外给 :rowActions（后端描述的行内操作）+ :rowBusy（每行各自的忙碌标记），
 *      点击抛 row-action 事件由上层去发请求，本级只负责渲染与禁用。
 */
const props = defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  empty: { type: String, default: '暂无数据' },
  /** 行内操作：[{ label, confirm }]；不给（手写面板）就完全不出现「操作」列 */
  rowActions: { type: Array, default: () => [] },
  /** 每行每个按钮各自独立的忙碌标记：{ '行下标:按钮下标': true }，缺省即都不忙 */
  rowBusy: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['row-action'])

function text(row, column) {
  const value = column.value ? column.value(row) : row[column.key]
  return value === undefined || value === null || value === '' ? '—' : value
}

/** 忙碌标记按「行 + 按钮」定位，所以点一行不会把整张表的按钮都禁掉 */
function busyAt(rowIndex, actionIndex) {
  return Boolean(props.rowBusy && props.rowBusy[rowIndex + ':' + actionIndex])
}
</script>

<template>
  <div class="table-wrap">
    <div v-if="!rows.length" class="empty">{{ empty }}</div>
    <table v-else>
      <thead>
        <tr>
          <th v-for="column in columns" :key="column.label">{{ column.label }}</th>
          <th v-if="$slots.actions || rowActions.length">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="row.taskId || row.userId || row.id || index">
          <td v-for="column in columns" :key="column.label" :data-label="column.label"
              :class="{ mono: column.mono, wide: column.wide }">
            <span v-if="column.tag" class="tag" :class="column.tag(row).tone">{{ column.tag(row).text }}</span>
            <template v-else>{{ text(row, column) }}</template>
          </td>
          <td v-if="$slots.actions || rowActions.length" data-label="操作">
            <slot name="actions" :row="row"></slot>
            <div v-if="rowActions.length" class="row">
              <button v-for="(action, actionIndex) in rowActions" :key="actionIndex" class="btn btn-sm"
                      :title="action.confirm || ''" :disabled="busyAt(index, actionIndex)"
                      @click="emit('row-action', { action, row, index, actionIndex })">
                {{ busyAt(index, actionIndex) ? '…' : action.label }}
              </button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
