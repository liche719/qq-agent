<script setup>
defineProps({
  label: { type: String, required: true },
  tone: { type: String, default: '' }
})
</script>

<template>
  <span class="pill" :class="tone">
    <i class="dot" aria-hidden="true"></i>{{ label }}
  </span>
</template>
