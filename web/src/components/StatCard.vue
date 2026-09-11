<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  meta: { type: String, default: '' },
  tone: { type: String, default: '' }
})
</script>

<template>
  <div class="glass stat">
    <div class="k">{{ label }}</div>
    <div class="v" :class="tone">{{ value }}</div>
    <div class="m">{{ meta }}</div>
  </div>
</template>
