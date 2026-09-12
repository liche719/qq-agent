import { auth, clearAuth } from './auth'

const TIMEOUT_MS = 15000

function messageFor(status) {
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '当前来源 IP 不允许访问运维面板'
  if (status === 429) return '请求过于频繁，已暂停访问，请稍后再试'
  if (status >= 500) return '服务端异常（' + status + '）'
  return '请求失败（' + status + '）'
}

async function request(path, { method = 'GET', body, key, onStatus } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  const headers = {}
  if (key) headers['X-Agent-Admin-Key'] = key
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
      signal: controller.signal
    })
  } catch (error) {
    throw new Error(error.name === 'AbortError' ? '请求超时（15 秒）' : '网络不可达')
  } finally {
    clearTimeout(timer)
  }
  if (onStatus) onStatus(response.status)
  if (!response.ok) {
    const error = new Error(messageFor(response.status))
    error.status = response.status
    throw error
  }
  // 204 或网关错误页都不是 JSON，直接 response.json() 会抛 SyntaxError（界面显示 "Unexpected token"）
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch (ignored) {
    throw new Error('服务端返回了无法解析的内容')
  }
}

/** 访问面板接口：自动带上当前标签页保存的口令 */
export function api(path, options = {}) {
  return request('/api/admin' + path, { ...options, key: auth.key }).catch(error => {
    if (error.status === 401) {
      clearAuth(true)
      // api.js 不能 import router（会循环依赖），hash 路由直接改 hash 即可
      if (window.location.hash !== '#/login') window.location.hash = '#/login'
    }
    throw error
  })
}

/** 登录：口令交给后端过滤器校验，账号由 /api/admin/session 校验 */
export async function login(username, password) {
  let status = 0
  await request('/api/admin/session', {
    method: 'POST',
    key: password,
    body: { username },
    onStatus: value => { status = value }
  }).catch(error => {
    if (status === 0) throw error
    const failed = new Error(status === 429 ? '尝试次数过多，已暂停访问，请稍后再试' : '账号或口令不正确')
    failed.status = status
    throw failed
  })
  return true
}
