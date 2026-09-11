import { reactive } from 'vue'

const AUTH_KEY = 'admin.auth'
const USERNAME_KEY = 'admin.username'

/** 登录态：默认只存在当前标签页；勾选“记住账号密码”后写入浏览器本地存储 */
export const auth = reactive({
  username: '',
  key: '',
  expired: false,
  remember: false
})

function parse(raw) {
  try {
    const parsed = JSON.parse(raw)
    return parsed && parsed.key ? parsed : null
  } catch (ignored) {
    return null
  }
}

function load() {
  try {
    const persistent = parse(localStorage.getItem(AUTH_KEY))
    if (persistent) {
      auth.remember = true
      return persistent
    }
    return parse(sessionStorage.getItem(AUTH_KEY))
  } catch (ignored) {
    return null
  }
}

const saved = load()
if (saved) {
  auth.username = saved.username || ''
  auth.key = saved.key || ''
}

/** 上次登录用的账号（用于登录页预填） */
export function lastUsername() {
  try {
    return localStorage.getItem(USERNAME_KEY) || ''
  } catch (ignored) {
    return ''
  }
}

export function hasAuth() {
  return Boolean(auth.key)
}

export function saveAuth(username, key, remember) {
  auth.username = username
  auth.key = key
  auth.remember = Boolean(remember)
  auth.expired = false
  try {
    const payload = JSON.stringify({ username, key })
    localStorage.removeItem(AUTH_KEY)
    sessionStorage.removeItem(AUTH_KEY)
    if (auth.remember) localStorage.setItem(AUTH_KEY, payload)
    else sessionStorage.setItem(AUTH_KEY, payload)
    localStorage.setItem(USERNAME_KEY, username)
  } catch (ignored) {
    /* 隐私模式下忽略 */
  }
}

export function clearAuth(expired = false) {
  const keepUsername = auth.remember
  auth.username = ''
  auth.key = ''
  auth.expired = Boolean(expired)
  try {
    localStorage.removeItem(AUTH_KEY)
    sessionStorage.removeItem(AUTH_KEY)
    if (!keepUsername) localStorage.removeItem(USERNAME_KEY)
  } catch (ignored) {
    /* 忽略 */
  }
}
