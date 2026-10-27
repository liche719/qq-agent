package com.liche.qqagent.tool;

import com.liche.qqagent.interview.InterviewService;
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
public class InterviewTool implements AgentToolProvider {

    private final InterviewService interviewService;
    private final ToolStatusService statusService;

    public InterviewTool(InterviewService interviewService, ToolStatusService statusService) {
        this.interviewService = interviewService;
        this.statusService = statusService;
    }

    @Tool(value = "开始模拟面试（他说「陪练」「模拟面试」时）。进入后每轮必须先记分再说话。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult startInterviewPractice(String role) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(interviewService.start(userId, role));
    }

    @Tool(value = "把这一轮问答记进评分卡：题类、题目、回答要点、四维 1~5 分、一句反馈。**每轮都要调，漏了这轮等于没练**。")
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

    @Tool(value = "结束陪练并出复盘（报告由程序按记录生成，不用你现写）。")
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
        return statusService.requireCurrentUserId();
    }
}
