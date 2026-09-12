package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.agent.CoachPresets;
import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.user.UserService;
import org.springframework.stereotype.Component;

/**
 * /practice（陪练）：切换英语陪练 / 面试陪练模式。
 *
 * <p>只改 {@code UserProfile.coachMode}，不动用户人设，因此随时可以退出、退出即恢复原样。
 */
@Component
public class PracticeHandler implements CommandHandler {

    private final UserService userService;

    public PracticeHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String name() {
        return "practice";
    }

    @Override
    public String description() {
        return "开始或结束陪练：/practice english（英语）、/practice interview（面试）、/practice off（结束）";
    }

    @Override
    public String handle(String args, String userId) {
        String raw = args == null ? "" : args.strip();
        try {
            if (raw.isEmpty() || raw.equalsIgnoreCase("help") || raw.contains("帮助") || raw.contains("说明")) {
                return usage();
            }
            if (CoachPresets.isOff(raw)) {
                String current = userService.coachMode(userId);
                userService.setCoachMode(userId, null);
                return current == null
                        ? "现在没有在陪练。想练的话发「陪练 英语」或「陪练 面试」。"
                        : "陪练结束，我们的说话方式恢复原样。想继续随时说「陪练 英语 / 陪练 面试」。";
            }
            String mode = CoachPresets.normalize(raw);
            if (mode == null) {
                return "我没听出来要练哪种。\n\n" + usage();
            }
            userService.setCoachMode(userId, mode);
            return CoachPresets.INTERVIEW.equals(mode)
                    ? """
                    好，进入「面试陪练」。我当面试官，一次问一个问题。
                    • 先告诉我：投的什么岗位、几年经验、什么方向（不说也行，我按通用岗位问）
                    • 每 3~5 轮我会给一次反馈和评分
                    • 想结束说「结束陪练」，你的说话风格会原样恢复"""
                    : """
                    好，进入「英语陪练」。我们用英语聊，难度跟着你走。
                    • 直接说一句英语就能开始，或者告诉我你想练什么场景（点餐 / 开会 / 闲聊…）
                    • 每轮我只挑 1~3 处最影响表达的错，给出更自然的说法
                    • 想结束说「结束陪练」，或者用中文说「讲解一下语法」我也会切过来解释""";
        } catch (BizException e) {
            return e.getMessage();
        }
    }

    private String usage() {
        return """
                陪练模式：
                • /practice english（或「陪练 英语」）— 英语口语陪练，边聊边纠错
                • /practice interview（或「陪练 面试」）— 面试陪练，我当面试官提问+反馈
                • /practice off（或「结束陪练」）— 退出陪练，恢复原来的说话方式

                陪练只加一层"练习要求"，你现在的人设和记忆都不会动。""";
    }
}
