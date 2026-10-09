package com.liche.wechatagent.tool;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.reminder.ReminderOperationResult;
import com.liche.wechatagent.reminder.ReminderParseService;
import com.liche.wechatagent.reminder.ReminderService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具2：定时提醒工具集。
 * parseReminder：@Tool 仅负责把自然语言解析为结构化数据，入库/校验/调度全部交由后端业务层（ReminderService）。
 */
@Component
public class ReminderTool implements AgentToolProvider {

    private final ReminderParseService parseService;
    private final ReminderService reminderService;
    private final ToolStatusService statusService;

    public ReminderTool(ReminderParseService parseService,
                        ReminderService reminderService,
                        ToolStatusService statusService) {
        this.parseService = parseService;
        this.reminderService = reminderService;
        this.statusService = statusService;
    }

    @Tool(value = "解析用户的提醒需求并创建定时提醒。用户提到'提醒我/帮我记着/XX点叫我/定时/每天/每周'等意图时调用。参数 description 是用户的原话。"
            + "**注意区分**：如果到点后需要你去做一件事（查天气、汇总内容、给建议等）再把结果发给他，请改用 createScheduledTask；"
            + "本工具只适合「到点发一句话提醒」的场景。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.MEDIUM, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult parseReminder(String description) {
        String userId = requireCurrentUser();
        ReminderParseService.ParsedReminder parsed = parseService.parse(description);
        return toToolResult(reminderService.createFromParsedResult(parsed, userId));
    }

    @Tool(value = "查询当前用户所有待执行的定时提醒任务。")
    public String listReminders() {
        return reminderService.listPendingText(requireCurrentUser());
    }

    @Tool(value = "取消一个定时提醒任务。参数 reminderId 为提醒的 ID（可通过 listReminders 查询）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.MEDIUM, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult cancelReminder(Long reminderId) {
        return toToolResult(reminderService.cancelResult(requireCurrentUser(), reminderId));
    }

    @Tool(value = "安全调整当前用户已有的定时提醒。先解析并验证新的时间，只有新提醒成功保存和调度后才会取消旧提醒；旧提醒 ID 通过 listReminders 或提醒状态查询获得。用户说‘改成/调整到/换成’已有提醒的新时间时优先调用。")
    // 不要声明 requiresConfirmation：它的校验依赖 confirmationParameter，而本方法没有任何令牌参数，
    // 写了等于挂一道永不生效的门（策略层对外声称 HIGH + 需确认，实现里零强制）。真实防线是工具描述 +
    // 系统提示词"改动前先复述给用户确认"，以及 service 侧的"新提醒成功落库后才取消旧的"。
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, destructive = true,
            riskLevel = ToolRiskLevel.HIGH, allowParallel = false, retryable = false)
    @NonIdempotentTool
    public ToolBusinessResult replaceReminder(Long reminderId, String description) {
        ReminderParseService.ParsedReminder parsed = parseService.parse(description);
        return toToolResult(reminderService.replaceFromParsed(parsed, requireCurrentUser(), reminderId));
    }

    @Tool(value = "查询当前用户最近提醒的真实状态，包括已推送、已过期、已取消和待执行；用户问‘提醒过吗/有没有收到/是不是没设置’时调用。")
    public String getReminderStatus() {
        return reminderService.listRecentStatusText(requireCurrentUser());
    }

    /**
     * 提醒业务结果 → 工具结果。**关键是三分而不是二分**：`needsInput` 表示"等用户补一句"，
     * 不是失败——它不该被标成 {@code 【工具执行失败】}（见 {@link ToolExecutionStatus#NEEDS_INPUT}）。
     */
    private ToolBusinessResult toToolResult(ReminderOperationResult result) {
        if (result.completed()) {
            return ToolBusinessResult.success(result.message());
        }
        return result.needsInput()
                ? ToolBusinessResult.needsInput(result.message())
                : ToolBusinessResult.failure(result.message());
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
