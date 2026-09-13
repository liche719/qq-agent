/** 接口返回的状态值统一翻成中文，界面不出现英文状态词 */
const DICTIONARY = {
  app: { UP: '正常', DEGRADED: '降级', DOWN: '异常' },
  qq: { UP: '正常', DOWN: '异常', DISABLED: '未启用' },
  dep: { UP: '正常', DOWN: '异常', STANDBY: '待机' },
  task: {
    RUNNING: '运行中',
    FAILED: '失败',
    UNKNOWN_RESULT: '结果未知',
    REPLY_SENT: '已回复',
    SUCCESS: '已成功',
    IDLE: '未执行',
    // 提醒任务（ReminderTask）也有自己的状态取值，文案与后端一致
    PENDING: '待执行',
    COMPLETED: '已推送',
    CANCELLED: '已取消',
    EXPIRED: '已过期，未补发'
  },
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
  action: { QQ_RECONNECT: '触发 QQ 重连', TASK_RETRY: '重试任务', CACHE_CLEANUP: '清理缓存' },
  // 长期记忆（core/work）的 MemoryStatus 取值
  memory: {
    ACTIVE: '生效中',
    ARCHIVED: '已归档',
    SUPERSEDED: '已被取代',
    PENDING: '待生效',
    EXPIRED: '已过期',
    DELETED: '已删除',
    COMPLETED: '已完成'
  },
  // 情景记忆的 MemoryStatus 取值
  episodic: { ACTIVE: '生效中', ARCHIVED: '已归档', SUPERSEDED: '已被取代' },
  // LLM 调用场景（面板「模型与搜索」的按场景表格）
  scenario: {
    dialog: '对话（默认档）',
    dialog_deep: '对话（模型申请升档）',
    extract: '记忆提取',
    reminder_parse: '提醒解析',
    schedule_parse: '定时任务解析',
    archive: '归档摘要',
    consolidate: '记忆归纳'
  }
}

export function zh(group, value) {
  if (value === undefined || value === null || value === '') return '—'
  const text = String(value)
  // 后端用 String.valueOf(null) 会产出字符串 "null"，这些也统一当空值
  if (text === 'null' || text === 'undefined' || text === 'NaN') return '—'
  return (DICTIONARY[group] && DICTIONARY[group][text]) || text
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

/**
 * 毫秒 → 人话时长。大数不再用千分位堆毫秒：
 *  < 1 秒 → 「812 毫秒」；< 1 分钟 → 「24.3 秒」；< 1 小时 → 「24 分 18 秒」；再往上 → 「3 小时 5 分」
 */
export function fmtDuration(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '—'
  if (number < 1000) return Math.round(number) + ' 毫秒'
  if (number < 60000) return (number / 1000).toFixed(1) + ' 秒'
  const totalSeconds = Math.round(number / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes < 60) return minutes + ' 分 ' + seconds + ' 秒'
  return Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分'
}

/** 后端耗时统一按毫秒给，这里交给 fmtDuration 决定用毫秒/秒/分显示 */
export function fmtMs(value) {
  return fmtDuration(value)
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
