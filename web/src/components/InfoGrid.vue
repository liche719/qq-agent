<script setup>
defineProps({
  rows: { type: Array, default: () => [] }
})
</script>

<template>
  <div class="kv">
    <div v-for="row in rows" :key="row[0]" class="kv-item">
      <div class="k">{{ row[0] }}</div>
      <div class="v">{{ row[1] }}</div>
    </div>
  </div>
</template>
