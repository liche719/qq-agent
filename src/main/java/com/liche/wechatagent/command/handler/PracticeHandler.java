package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.agent.CoachPresets;
import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.interview.InterviewService;
import com.liche.wechatagent.user.UserService;
import org.springframework.stereotype.Component;

/**
 * /practice（陪练）：进入或结束**面试陪练**。
 *
 * <p>只改 {@code UserProfile.coachMode/coachSessionId}，不动用户人设；结束时会根据评分卡
 * 生成一份复盘报告（由程序生成，不经过大模型）。
 */
@Component
public class PracticeHandler implements CommandHandler {

    /** 「陪练 <词>」里表示"就练面试这个模式"的词（不是岗位） */
    private static final java.util.Set<String> MODE_WORDS = java.util.Set.of(
            "interview", "面试", "面试陪练", "模拟面试", "陪练", "开始陪练");

    private final UserService userService;
    private final InterviewService interviewService;

    public PracticeHandler(UserService userService, InterviewService interviewService) {
        this.userService = userService;
        this.interviewService = interviewService;
    }

    @Override
    public String name() {
        return "practice";
    }

    @Override
    public String description() {
        return "面试陪练：/practice interview 开始（或「陪练 面试」），/practice off 结束并出复盘";
    }

    @Override
    public String handle(String args, String userId) {
        String raw = args == null ? "" : args.strip();
        try {
            if (CoachPresets.isOff(raw)) {
                String report = interviewService.finish(userId);
                if (report != null) {
                    return report;
                }
                String current = userService.coachMode(userId);
                userService.endInterviewSession(userId);
                return current == null
                        ? "现在没有在陪练。想练的话发「陪练 面试」。"
                        : "陪练结束，我们的说话方式恢复原样。想继续随时说「陪练 面试」。";
            }
            if (raw.isEmpty() || raw.equalsIgnoreCase("help") || raw.contains("帮助") || raw.contains("说明")) {
                return usage();
            }
            // 目前只有面试陪练一种模式，任何非 off 的参数都按"开始面试陪练"处理，岗位信息从参数里取
            String role = isModeWord(raw) ? "" : raw;
            interviewService.start(userId, role);
            return "好，进入「面试陪练」。我当面试官，一次问一个问题；每轮会记进评分卡，随时说「结束陪练」我给你复盘报告。"
                    + (role.isBlank() ? "\n先告诉我：投的什么岗位、几年经验、什么方向（不说也行，我按通用岗位问）。" : "\n本次岗位：" + role);
        } catch (BizException e) {
            return e.getMessage();
        }
    }

    /**
     * 「陪练 面试」这种写法里，「面试」是模式词而不是岗位——不能当成岗位记进评分卡，
     * 否则提示词和复盘标题会出现「本次岗位：面试」，开场也不再追问岗位。
     */
    private static boolean isModeWord(String raw) {
        return MODE_WORDS.contains(raw.toLowerCase(java.util.Locale.ROOT));
    }

    private String usage() {
        return """
                面试陪练：
                • 「陪练 面试」或 /practice interview — 我当面试官，一次一个问题，逐轮评分
                • 也可以直接说岗位，例如「陪练 Java 后端 3 年」，出的题会贴这个方向
                • 「结束陪练」或 /practice off — 结束并给你一份复盘报告（各维度均分、最弱项、下次重点）

                题库覆盖：自我介绍 / 项目深挖 / 技术基础 / 系统设计 / 行为面试 / 反问环节；
                每轮的分数会记进评分卡，退出时的复盘报告由程序按记录生成，不是模型随口总结。""";
    }
}
