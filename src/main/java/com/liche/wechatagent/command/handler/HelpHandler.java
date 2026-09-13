package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

/** /help：查看所有可用指令和功能说明（不依赖 CommandRegistry，避免构造循环） */
@Component
public class HelpHandler implements CommandHandler {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "查看所有可用指令和功能说明";
    }

    @Override
    public String handle(String args, String userId) {
        return "我现在能帮你：\n"
                + "• 自动记住长期目标、稳定偏好和重要经历，不会询问要不要保存\n"
                + "• 阅读图片、PDF、DOCX；对未来仍有价值的资料会自动长期保管\n"
                + "• 搜索最新资料、读取网页、设置和取消提醒\n"
                + "• 定时任务：说「每天早上 8 点把今天的天气发我」「每周一汇总上周聊过的重点」，到点我会真的去做完再把结果发你\n"
                + "• 陪你模拟面试（说「陪练 面试」或「陪练 Java 后端 3 年」），逐轮评分、结束给复盘\n"
                + "• 考研规划：排计划、发每日任务、催进度（说「考研 2026-12-20 报考XX大学 计算机，科目 数学:120:120:强化第3章」建计划）\n"
                + "• 在你主动开启后，按每天或每周做一次低打扰目标复盘\n\n"
                + "可用指令：\n"
                + "/memory — 查看自动记忆\n"
                + "/memory forget 关键词 — 按内容删除一条记忆\n"
                + "/memory on|off — 开关自动记忆\n"
                + "/reminders — 查看待执行提醒\n"
                + "/schedules — 查看定时任务（off|on|run|delete <ID> 可暂停/恢复/立即执行/删除）\n"
                + "/care on|off|daily|weekly — 管理主动关怀频率\n"
                + "/practice interview|off — 面试陪练，也可以直接发「陪练 面试」「结束陪练」\n"
                + "/exam — 考研计划与今日安排（也可以直接发「考研」）\n"
                + "/exam-today — 今天的考研任务（没有就按计划生成，也可发「今日任务」）\n"
                + "/exam-progress — 考研进度（也可发「考研进度」）\n"
                + "/checkin [时长] — 考研打卡，例如「打卡 150」或「打卡 3 小时」\n"
                + "/set-prompt 内容 — 调整我的身份和说话方式\n"
                + "/help — 再看一次这份说明";
    }
}
