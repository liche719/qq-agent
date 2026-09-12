package com.liche.wechatagent.tool;

import com.liche.wechatagent.interview.InterviewService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具：面试陪练开关与评分卡记录。
 *
 * <p>用户用自然语言说「你当面试官陪我练练」「模拟一下面试」时，由模型调用 {@code startInterviewPractice}
 * **真正进入陪练模式**（写入 {@code user_profile.coach_mode} + 建 session），而不是临时扮演；
 * 每轮结束后用 {@code recordInterviewRound} 记分，退出时程序给出复盘报告。
 */
@Component
public class InterviewTool {

    private final InterviewService interviewService;
    private final ToolStatusService statusService;

    public InterviewTool(InterviewService interviewService, ToolStatusService statusService) {
        this.interviewService = interviewService;
        this.statusService = statusService;
    }

    @Tool(value = "开始面试陪练（模拟面试）。用户用自然语言要求模拟面试、当面试官问他时调用"
            + "（例如「你当面试官陪我练练」「模拟一下面试」「帮我准备面试」）。role 传用户说的岗位与年限（例如「Java 后端 3 年」），没说就传空字符串。"
            + "进入后会一直生效到用户要求结束。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult startInterviewPractice(String role) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(interviewService.start(userId, role));
    }

    @Tool(value = "把刚结束的这一轮面试问答记进评分卡：题类、题目、回答要点、四个维度 1~5 分（内容完整度/结构清晰度/技术深度/表达流畅度）、一句反馈。"
            + "**每轮都必须调用，而且要在写反馈之前先调用**（先记分再说话）；只处理对方真正答了一道面试题的那一轮，对方只是闲聊澄清时不要调用。"
            + "工具会返回当前均分、已问题目和还没覆盖的题类。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = true,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    public ToolBusinessResult recordInterviewRound(String category, String question, String answerSummary,
                                                   Integer scoreContent, Integer scoreStructure,
                                                   Integer scoreDepth, Integer scoreDelivery,
                                                   String feedback) {
        String userId = requireCurrentUser();
        String result = interviewService.record(userId, category, question, answerSummary,
                scoreContent, scoreStructure, scoreDepth, scoreDelivery, feedback);
        return ToolBusinessResult.success(result);
    }

    @Tool(value = "结束面试陪练并生成复盘报告。用户说不想练了/结束面试/先到这（或直接说结束陪练）时调用。"
            + "返回的复盘内容包括轮数、各维度均分、最弱项和下次重点，请把它完整转述给用户。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult endInterviewPractice() {
        String userId = requireCurrentUser();
        String report = interviewService.finish(userId);
        return ToolBusinessResult.success(report == null
                ? "本次没有记录到问答（还没开始练）。请一句话告知用户现在不在陪练，并说明说「陪练 面试」可以开始。"
                : report + "\n\n（以上是程序根据评分卡生成的复盘，请原样转述给用户，不要改写数字。）");
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
