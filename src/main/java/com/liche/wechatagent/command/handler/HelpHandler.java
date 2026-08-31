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
                + "• 阅读图片、PDF、DOCX；课表、证书等重要资料会自动长期保管\n"
                + "• 搜索最新资料、读取网页、设置和取消提醒\n"
                + "• 在你主动开启后，按每天或每周做一次低打扰目标复盘\n\n"
                + "可用指令：\n"
                + "/memory — 查看自动记忆\n"
                + "/memory forget 南京理工 — 按内容删除一条记忆\n"
                + "/memory on|off — 开关自动记忆\n"
                + "/reminders — 查看待执行提醒\n"
                + "/care on|off|daily|weekly — 管理主动关怀频率\n"
                + "/set-prompt 内容 — 调整我的身份和说话方式\n"
                + "/help — 再看一次这份说明";
    }
}
