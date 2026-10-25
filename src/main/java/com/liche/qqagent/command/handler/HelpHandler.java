package com.liche.qqagent.command.handler;

import com.liche.qqagent.command.CommandHandler;
import org.springframework.stereotype.Component;

/**
 * /help：查看可用指令（不依赖 CommandRegistry，避免构造循环）。
 *
 * <p>2026-10-25 用户要求：**只保留三条**——定时任务（提醒并进来）、人设提示词、帮助。
 * 复盘/关怀那套整块删了；记忆、陪练这些仍然能用，只是不再列在帮助里（发对应的话照样生效）。
 */
@Component
public class HelpHandler implements CommandHandler {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "查看可用指令";
    }

    @Override
    public String handle(String args, String userId) {
        return """
                我现在能帮你：
                • 自动记住长期目标、稳定偏好和重要经历，不会问你"要不要保存"
                • 读图片、PDF、DOCX；对未来有用的资料会自动长期保管
                • 搜索最新资料、读网页、看 B 站视频、画图、发语音
                • 陪你练面试（直接说「陪练 面试」）

                可用指令（就三个）：
                • 「定时任务」或「查看提醒」— 看你的提醒和定时任务（/schedules）
                  暂停/恢复/立即执行：/schedules off|on|run <ID>
                  新建不用打指令，直接说「每天早上 8 点把今天的天气发我」
                • 「设置助手人设 内容」— 调整我的身份和说话方式（/set-prompt）
                • 「帮助」— 再看一次这份说明（/help）
                """;
    }
}
