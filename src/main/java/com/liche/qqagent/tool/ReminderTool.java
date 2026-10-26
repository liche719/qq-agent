package com.liche.qqagent.tool;

import com.liche.qqagent.exception.BizException;
import com.liche.qqagent.reminder.ReminderOperationResult;
import com.liche.qqagent.reminder.ReminderParseService;
import com.liche.qqagent.reminder.ReminderService;
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

    @Tool(value = "创建**一次性**提醒：到点只发一句话（「7 点提醒我吃药」「明天叫我起床」）。**每天/每周重复做的事 → createScheduledTask**。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.MEDIUM, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult parseReminder(String description) {
        String userId = requireCurrentUser();
        ReminderParseService.ParsedReminder parsed = parseService.parse(description);
        return toToolResult(reminderService.createFromParsedResult(parsed, userId));
    }

    @Tool(value = "列出还没到点的提醒。")
    public String listReminders() {
        return reminderService.listPendingText(requireCurrentUser());
    }

    @Tool(value = "取消一个提醒（id 从 listReminders 拿）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.MEDIUM, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult cancelReminder(Long reminderId) {
        return toToolResult(reminderService.cancelResult(requireCurrentUser(), reminderId));
    }

    @Tool(value = "改一个已有提醒的时间：先建新的、成功后再取消旧的，不会出现空窗。id 从 listReminders 拿。")
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

    @Tool(value = "查提醒的真实状态（已推送/已过期/已取消/待执行）。他问「提醒过吗」「是不是没设」时用。")
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
