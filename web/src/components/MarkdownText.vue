<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 安全地把 Markdown 渲染成 HTML。
 *
 * 聊天回复、长期记忆、定时任务结果里都是模型产出的 Markdown（粗体、列表、表格、代码块、引用），
 * 直接 `{{ }}` 插值会把 `**` 原样显示出来；但这些都是**用户数据**，所以**绝不能**直接把原文塞进 `v-html`：
 * 这里先用 marked 解析、再用 DOMPurify 白名单清洗（去掉 script/style/内联事件/javascript: 链接等），
 * 只允许常规排版标签。链接统一加 target=_blank + rel=noopener。
 */
const props = defineProps({
  text: { type: [String, Number], default: '' },
  inline: { type: Boolean, default: false }
})

marked.setOptions({ gfm: true, breaks: true })

DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

const html = computed(() => {
  const source = props.text === null || props.text === undefined ? '' : String(props.text)
  if (!source.trim()) return ''
  const rendered = props.inline ? marked.parseInline(source) : marked.parse(source)
  return DOMPurify.sanitize(rendered, { USE_PROFILES: { html: true } })
})
</script>

<template>
  <div class="md" :class="{ 'md-inline': inline }" v-html="html"></div>
</template>
