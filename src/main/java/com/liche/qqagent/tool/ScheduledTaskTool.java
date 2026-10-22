package com.liche.qqagent.tool;

import com.liche.qqagent.schedule.ScheduledTaskService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具：定时任务（到点让机器人真的去做一件事，并把结果发回来）。
 *
 * <p>和"定时提醒"的区别：提醒只是到点发一句话；定时任务会**用完整 Agent 能力执行**——
 * 例如「每天早上 8 点把今天的天气发我」到点会真的去查天气再发。
 * 要不要调用完全由模型按用户意图判断（不做关键词硬编码）。
 */
@Component
public class ScheduledTaskTool implements AgentToolProvider {

    private final ScheduledTaskService scheduledTaskService;
    private final ToolStatusService statusService;

    public ScheduledTaskTool(ScheduledTaskService scheduledTaskService, ToolStatusService statusService) {
        this.scheduledTaskService = scheduledTaskService;
        this.statusService = statusService;
    }

    @Tool(value = "创建重复执行的定时任务：到点后由你真正去完成任务并把结果发给用户。"
            + "当用户要求「每天/每周/每隔一段时间」做一件需要你动手的事时调用"
            + "（例如「每天早上 8 点把今天的天气发我」「每周一帮我汇总上周聊过的重点」「每天晚上 9 点提醒我复盘今天的进度并给建议」）。"
            + "参数名就是 **task**：把「到点要做什么」按他的原话写进去。"
            + "注意：如果只是到点提醒一句话、不需要你做事，应该用 parseReminder 而不是这个工具。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult createScheduledTask(
            @dev.langchain4j.agent.tool.P("到点要做什么，用他的原话，例如「每天早上 8 点把天气发我」") String task) {
        String userId = requireCurrentUser();
        // 2026-10-22：参数名从 description 改成 task（模型一直传 task，传错就静默变空、反复重试）。
        // 真为空时必须回**失败**，不能 success 包一句"没听清"——那会让模型以为成功、继续乱试。
        if (task == null || task.isBlank()) {
            return ToolBusinessResult.failure("缺少参数 task：把「到点要做什么」写成一句话传进来，"
                    + "例如 task=「每天早上 8 点把天气发我」。不要用 description/name 这些键名。");
        }
        String result = scheduledTaskService.createFromDescription(userId, task);
        // 服务层用"字符串"表达失败（没听清/还差点信息/功能关闭…）。以前统一包成 success，
        // 模型以为建成了、其实没有，还反复重试到轮次耗尽（2026-10-22 实测）。
        return looksLikeFailure(result) ? ToolBusinessResult.failure(result) : ToolBusinessResult.success(result);
    }

    private static boolean looksLikeFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        return result.startsWith("没听清") || result.startsWith("还差点信息")
                || result.startsWith("Cron 表达式不合法") || result.startsWith("要执行什么还没说清楚")
                || result.startsWith("定时任务已经有") || result.contains("功能当前关闭");
    }

    @Tool(value = "查询当前用户所有的定时任务（含启用状态、执行频率、下次执行时间、上次结果）。"
            + "用户问「我有哪些定时任务」「定时任务都在跑吗」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = true)
    public ToolBusinessResult listScheduledTasks() {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.listText(userId));
    }

    @Tool(value = "暂停或恢复一个定时任务。参数 taskId 是任务 ID（先用 listScheduledTasks 查），"
            + "enabled 传 true 表示恢复执行、false 表示暂停。用户说「停掉/别再跑了/恢复那个定时任务」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult setScheduledTaskEnabled(Long taskId, Boolean enabled) {
        String userId = requireCurrentUser();
        boolean value = enabled == null || enabled;
        return ToolBusinessResult.success(scheduledTaskService.setEnabled(userId, taskId, value));
    }

    @Tool(value = "删除一个定时任务（不可恢复）。参数 taskId 是任务 ID（先用 listScheduledTasks 查）。"
            + "用户明确说「删掉/取消这个定时任务」时调用；只是暂时不想跑请用 setScheduledTaskEnabled 暂停。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, destructive = true,
            retryable = false, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult cancelScheduledTask(Long taskId) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.cancel(userId, taskId));
    }

    @Tool(value = "立刻执行一次某个定时任务（不影响原定计划）。参数 taskId 是任务 ID。"
            + "用户说「现在就跑一下那个定时任务」「先试一次」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult runScheduledTaskNow(Long taskId) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.runNow(userId, taskId));
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
