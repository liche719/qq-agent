/**
 * 核心页签注册表：key → 手写面板组件（原来硬编码在 DashboardView 的 TABS + v-else-if 里）。
 *
 * 页签清单改由后端 GET /api/admin/panels 下发：
 *   kind = "core"       → 用这里的组件渲染；
 *   kind = "descriptor" → 用 components/DescriptorPanel.vue 按后端描述渲染。
 * 后端新增模块时只要给出 descriptor 描述即可，前端不用改、也不用重新构建。
 * 这里的 key 同时是清单接口不可用时的兜底页签（中文名见 CORE_TAB_LABELS）。
 */
import OverviewPanel from './OverviewPanel.vue'
import QqPanel from './QqPanel.vue'
import LlmPanel from './LlmPanel.vue'
import MaimemoPanel from './MaimemoPanel.vue'
import TasksPanel from './TasksPanel.vue'
import ScheduledPanel from './ScheduledPanel.vue'
import UsersPanel from './UsersPanel.vue'
import LogsPanel from './LogsPanel.vue'

export const CORE_PANELS = {
  overview: OverviewPanel,
  qq: QqPanel,
  llm: LlmPanel,
  maimemo: MaimemoPanel,
  tasks: TasksPanel,
  scheduled: ScheduledPanel,
  users: UsersPanel,
  logs: LogsPanel
}

/** 清单接口挂掉时用的中文页签名（也是后端漏给 label 时的兜底） */
export const CORE_TAB_LABELS = {
  overview: '总览',
  qq: 'QQ 通道',
  llm: '模型与搜索',
  maimemo: '背单词',
  tasks: '任务',
  scheduled: '定时任务',
  users: '用户与记忆',
  logs: '日志'
}

/** 兜底页签清单，顺序与 CORE_TAB_LABELS 一致 */
export const CORE_TABS = Object.keys(CORE_TAB_LABELS).map(key => ({
  key,
  label: CORE_TAB_LABELS[key],
  kind: 'core'
}))
