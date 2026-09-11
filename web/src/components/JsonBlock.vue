<script setup>
defineProps({
  title: { type: String, required: true },
  data: { type: [Object, Array], default: null },
  open: { type: Boolean, default: false }
})
</script>

<template>
  <details class="block" :open="open">
    <summary>{{ title }}</summary>
    <pre class="json">{{ JSON.stringify(data, null, 2) }}</pre>
  </details>
</template>
