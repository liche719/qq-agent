/** 接口返回的状态值统一翻成中文，界面不出现英文状态词 */
const DICTIONARY = {
  app: { UP: '正常', DEGRADED: '降级', DOWN: '异常' },
  qq: { UP: '正常', DOWN: '异常', DISABLED: '未启用' },
  dep: { UP: '正常', DOWN: '异常', STANDBY: '待机' },
  task: { RUNNING: '运行中', FAILED: '失败', UNKNOWN_RESULT: '结果未知', REPLY_SENT: '已回复' },
  channel: { qq: 'QQ', wechat: '微信', wechat_ilink: '微信', clawbot: '微信', simulator: '模拟器' },
  source: { operation: '操作审计', application: '应用日志' },
  maimemo: {
    OK: '已连接',
    NOT_CONFIGURED: '未配置 Token',
    UNAUTHORIZED: 'Token 失效',
    ERROR: '读取失败',
    DISABLED: '未启用'
  },
  level: { INFO: '信息', WARN: '警告', ERROR: '错误' },
  action: { QQ_RECONNECT: '触发 QQ 重连', TASK_RETRY: '重试任务', CACHE_CLEANUP: '清理缓存' }
}

export function zh(group, value) {
  if (value === undefined || value === null || value === '') return '—'
  return (DICTIONARY[group] && DICTIONARY[group][value]) || String(value)
}

export function toneOf(value) {
  if (value === 'UP') return 'ok'
  if (value === 'DEGRADED' || value === 'STANDBY' || value === 'DISABLED' || value === 'RUNNING') return 'warn'
  return 'bad'
}

export function taskTone(status) {
  if (status === 'FAILED' || status === 'UNKNOWN_RESULT') return 'bad'
  if (status === 'REPLY_SENT') return 'ok'
  return 'warn'
}

export function fmtBytes(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '—'
  const units = ['字节', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  let size = number
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  const text = index === 0 ? String(size) : size.toFixed(size >= 100 ? 0 : 1)
  return text + ' ' + units[index]
}

export function fmtNum(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return value === undefined || value === null || value === '' ? '—' : String(value)
  return number.toLocaleString('zh-CN')
}

export function fmtMs(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? Math.round(number) + ' 毫秒' : '—'
}

export function fmtTime(value) {
  if (value === undefined || value === null || value === '') return '—'
  const text = String(value)
  // 后端用 String.valueOf(null) 会产出字符串 "null"，这里统一当空值处理
  if (text === 'null' || text === 'undefined' || text === 'NaN') return '—'
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)) return text
  const parsed = new Date(text)
  if (Number.isNaN(parsed.getTime())) return text
  const pad = number => String(number).padStart(2, '0')
  return parsed.getFullYear() + '-' + pad(parsed.getMonth() + 1) + '-' + pad(parsed.getDate())
    + ' ' + pad(parsed.getHours()) + ':' + pad(parsed.getMinutes()) + ':' + pad(parsed.getSeconds())
}
