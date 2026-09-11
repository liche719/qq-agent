import { reactive } from 'vue'

const STORAGE_KEY = 'admin.auth'

/** 登录态：口令只存在当前标签页，关闭浏览器即失效 */
export const auth = reactive({
  username: '',
  key: '',
  expired: false
})

function read() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return parsed && parsed.key ? parsed : null
  } catch (ignored) {
    return null
  }
}

const saved = read()
if (saved) {
  auth.username = saved.username || ''
  auth.key = saved.key || ''
}

export function hasAuth() {
  return Boolean(auth.key)
}

export function saveAuth(username, key) {
  auth.username = username
  auth.key = key
  auth.expired = false
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ username, key }))
  } catch (ignored) {
    /* 隐私模式下忽略 */
  }
}

export function clearAuth(expired = false) {
  auth.username = ''
  auth.key = ''
  auth.expired = Boolean(expired)
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch (ignored) {
    /* 忽略 */
  }
}
