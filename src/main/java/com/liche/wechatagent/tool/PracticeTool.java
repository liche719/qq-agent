package com.liche.wechatagent.tool;

import com.liche.wechatagent.agent.CoachPresets;
import com.liche.wechatagent.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具：陪练模式开关（英语口语 / 模拟面试）。
 *
 * <p>用户用自然语言说「陪我练练英语」「你当面试官问问我」时，由模型调用本工具**真正进入陪练模式**
 * （写入 {@code user_profile.coach_mode} 并给系统提示词追加该模式的要求），而不是模型临时扮演：
 * 临时扮演不持久、规则不稳定，下一轮就可能跑回普通聊天。
 */
@Component
public class PracticeTool {

    private final UserService userService;
    private final ToolStatusService statusService;

    public PracticeTool(UserService userService, ToolStatusService statusService) {
        this.userService = userService;
        this.statusService = statusService;
    }

    @Tool(value = "进入陪练模式。用户用自然语言要求练英语口语、或让你当面试官模拟面试时调用（例如「陪我练练英语」「你当面试官问问我」「模拟一下面试」）。"
            + "参数 mode 只传 english 或 interview。进入后系统会按该模式的规则长期生效，直到用户要求结束。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = true,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    public ToolBusinessResult startPractice(String mode) {
        String userId = requireCurrentUser();
        String normalized = CoachPresets.normalize(mode);
        if (normalized == null) {
            return ToolBusinessResult.failure("无法识别陪练类型，只支持 english（英语口语）或 interview（模拟面试）");
        }
        userService.setCoachMode(userId, normalized);
        if (CoachPresets.INTERVIEW.equals(normalized)) {
            return ToolBusinessResult.success("已进入「面试陪练」模式（会一直生效到用户要求结束）。"
                    + "接下来你扮演面试官：一次只问一个问题，问完就停下等对方回答；对方答完先简短追问细节，每 3~5 轮给一次「✅亮点／⚠️风险／💡改法」并给 1~5 星评分；"
                    + "不知道岗位就先问岗位、年限、方向。现在先用一句话确认已开始，然后问第一个问题（自我介绍类即可）。");
        }
        return ToolBusinessResult.success("已进入「英语陪练」模式（会一直生效到用户要求结束）。"
                + "接下来用英语对话：先自然回应对方 1~3 句，再只挑 1~3 处最影响表达的错，用「你说：… → 更自然：…（一句中文解释）」纠正，最后用一个问题把对话推下去；不要长篇讲解。"
                + "现在先用一句话确认已开始，然后给一个开场问题（不知道水平就用简单问题探一下）。");
    }

    @Tool(value = "退出陪练模式（英语口语 / 模拟面试）。用户说不想练了、结束练习、退出陪练、先不练了时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = true,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    public ToolBusinessResult stopPractice() {
        String userId = requireCurrentUser();
        String current = userService.coachMode(userId);
        userService.setCoachMode(userId, null);
        return ToolBusinessResult.success(current == null
                ? "当前本来就不在陪练模式；请一句话告知用户现在没有在陪练，并说明发「陪练 英语」或「陪练 面试」可以开始。"
                : "已退出陪练模式，恢复正常说话方式；请一句话确认即可，不要多解释。");
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
