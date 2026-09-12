package com.liche.wechatagent.agent;

import com.liche.wechatagent.interview.InterviewBank;

import java.util.Locale;

/**
 * 陪练模式的系统提示词（目前只有「面试陪练」一种）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不动用户人设</b>——只追加一段本模式的额外要求，退出即原样恢复；</li>
 *   <li>题目来自 {@link InterviewBank}，逐轮评分由工具写进 {@code interview_round}，
 *       结束时的复盘报告由程序根据记录生成，不依赖模型临场记忆。</li>
 * </ul>
 */
public final class CoachPresets {

    public static final String INTERVIEW = "interview";
    public static final String INTERVIEW_LABEL = "面试陪练";

    private static final String INTERVIEW_DIRECTIVE = """
            【当前处于「面试陪练」模式，以下要求优先于一般聊天习惯】
            1. 你扮演面试官：一次只问一个问题，问完就停下等对方回答；不要连问多个问题。
            2. **每轮的顺序是固定的**：对方答完一题，你这一轮回复必须① **先调用 recordInterviewRound** 把这轮记进评分卡（题类、题目、回答要点、四个分数、一句反馈），② 再写 2~4 句具体反馈（亮点 + 一个改法），③ 最后追问或用一句话问下一题。**顺序不能颠倒**——先记分再说话，漏调工具这一轮就等于没练。
            3. 评分：四个维度各打 1~5 分——内容完整度、结构清晰度、技术深度、表达流畅度，评分参照题库里该题类的"评分观察点"，不要随心情给分；分数要和 recordInterviewRound 里传的一致。
            4. 只有"对方回答了一道面试题"才算一轮。对方只是在澄清、闲聊、问你怎么用、说"等等我想想"，就不记分、也不用给反馈，正常回一句就行。
            5. 选题来自下面题库，**不要重复问已经问过的题**；岗位相关的内容优先顺着对方刚说的项目/技术栈深挖，模拟真实面试的追问节奏。
            6. 一次面试 6~10 轮为宜；对方答得含糊就继续追问细节（要数字、要取舍、要原因），把答案逼具体。
            7. 不要替对方写答案：可以示范"更好的说法"，但要让对方自己再讲一遍。
            8. 单条回复尽量不超过 350 字；对方说不想练了/结束面试时，调用 endInterviewPractice（程序会给出一份复盘报告）。
            9. 开场先确认对方投的岗位和年限（如果还没说过），再问第一题。

            【题库与评分观察点】
            %s
            """;

    private CoachPresets() {
    }

    /** 是否是"关闭陪练"的输入。 */
    public static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return value.contains("off") || value.contains("end") || value.contains("stop")
                || value.contains("结束") || value.contains("停止") || value.contains("退出")
                || value.contains("关闭") || value.contains("不练") || value.contains("取消");
    }

    /** 把陪练要求拼到人设后面；未开启时原样返回人设。 */
    public static String withMode(String persona, String coachMode) {
        if (coachMode == null || !INTERVIEW.equalsIgnoreCase(coachMode.strip())) {
            return persona;
        }
        String base = persona == null ? "" : persona.strip();
        String directive = INTERVIEW_DIRECTIVE.formatted(InterviewBank.asPromptText());
        return base.isEmpty() ? directive : base + "\n\n" + directive;
    }

    /** 模式的中文名；未知返回 null。 */
    public static String label(String mode) {
        return mode != null && INTERVIEW.equalsIgnoreCase(mode.strip()) ? INTERVIEW_LABEL : null;
    }
}
