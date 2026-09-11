// 黑白主题切换：白色（默认，就是原来的样子）/ 黑色（同一套玻璃与网格，只是色调转深）
// 选择存 localStorage；没有存过时跟随系统偏好。
import { reactive } from 'vue'

const KEY = 'admin.theme'

function read() {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved === 'dark' || saved === 'light') return saved
  } catch (e) { /* 隐私模式下 localStorage 不可用 */ }
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } catch (e) {
    return 'light'
  }
}

export const theme = reactive({ mode: read() })

function apply() {
  document.documentElement.dataset.theme = theme.mode
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', theme.mode === 'dark' ? '#0a0d14' : '#f7f9fd')
  try { localStorage.setItem(KEY, theme.mode) } catch (e) { /* 忽略 */ }
}

export function toggleTheme() {
  theme.mode = theme.mode === 'dark' ? 'light' : 'dark'
  apply()
}

// 按钮文案：显示的是「点了会切到哪个主题」
export function themeLabel() {
  return theme.mode === 'dark' ? '浅色' : '深色'
}

apply()
