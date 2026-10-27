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

    @Tool(value = "创建**重复执行**的任务：他说「每天/每周/固定几点」时**直接用它**，先别查记忆；到点由你真正去做完再发结果。**一次性提醒 → parseReminder**。")
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

    @Tool(value = "列出所有定时任务（状态、频率、下次时间、上次结果）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = true)
    public ToolBusinessResult listScheduledTasks() {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.listText(userId));
    }

    @Tool(value = "暂停或恢复一个定时任务（id 从 listScheduledTasks 拿）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult setScheduledTaskEnabled(Long taskId, Boolean enabled) {
        String userId = requireCurrentUser();
        boolean value = enabled == null || enabled;
        return ToolBusinessResult.success(scheduledTaskService.setEnabled(userId, taskId, value));
    }

    @Tool(value = "删除一个定时任务，不可恢复（id 从 listScheduledTasks 拿）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, destructive = true,
            retryable = false, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult cancelScheduledTask(Long taskId) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.cancel(userId, taskId));
    }

    @Tool(value = "立刻跑一次某个定时任务，不影响原定计划。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult runScheduledTaskNow(Long taskId) {
        String userId = requireCurrentUser();
        return ToolBusinessResult.success(scheduledTaskService.runNow(userId, taskId));
    }

    private String requireCurrentUser() {
        return statusService.requireCurrentUserId();
    }
}
