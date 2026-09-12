<script setup>
import { onMounted, ref } from 'vue'

// 备案号由后端 /api/site/info 提供；没配置时整块页脚不渲染
const icp = ref('')

onMounted(async () => {
  try {
    const response = await fetch('/api/site/info', { cache: 'no-store' })
    if (!response.ok) return
    const data = await response.json()
    icp.value = (data && data.icp) || ''
  } catch (ignored) {
    /* 取不到就不显示，不影响面板使用 */
  }
})
</script>

<template>
  <footer v-if="icp" class="site-footer">
    <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">{{ icp }}</a>
  </footer>
</template>
