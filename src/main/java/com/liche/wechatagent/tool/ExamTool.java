package com.liche.wechatagent.tool;

import com.liche.wechatagent.exam.ExamService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具：考研规划 —— 备考计划、每日任务、打卡与进度。
 *
 * <p>计划/任务/打卡都落库，进度数字由 {@link ExamService} 按数据算，不靠模型记；
 * 模型只负责在合适的时候调用这些工具并把结果转述给用户。自然语言入口例如
 * 「我 12 月 20 号考研，考数学和英语」「今天数学那项做完了」「打卡 150 分钟」。
 */
@Component
public class ExamTool implements AgentToolProvider {

    private final ExamService examService;
    private final ToolStatusService statusService;

    public ExamTool(ExamService examService, ToolStatusService statusService) {
        this.examService = examService;
        this.statusService = statusService;
    }

    @Tool(value = "查看考研备考计划（目标院校/专业/考试日期/阶段/每天时长/科目与目标分）。"
            + "用户问「我的考研计划」「考研目标是什么」时调用。返回的文本请原样转述，数字不要改写。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult viewExamPlan() {
        return ToolBusinessResult.success(examService.planText(requireCurrentUser()));
    }

    @Tool(value = "新建或更新考研备考计划。subjects 用分号分隔科目，每个科目四个字段用冒号分隔："
            + "科目名:目标分:每天分钟:每日计划，例如「数学:120:120:强化第3章;英语:70:60:阅读2篇+单词50;政治:70:60:刷题;专业课:110:120:真题」。"
            + "只有科目名必填。examDate 传用户说的考试日期（YYYY-MM-DD，不知道具体日期时可以传空），"
            + "stage 传 基础/强化/冲刺。用户第一次说考研目标、或要求改计划时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult saveExamPlan(String examDate, String school, String major, String stage,
                                           Integer dailyMinutes, String subjects, String remark) {
        return ToolBusinessResult.success(examService.savePlan(requireCurrentUser(), examDate, school, major,
                stage, dailyMinutes, subjects, remark));
    }

    @Tool(value = "列出某一天的考研任务（不传 date 就是今天）。用户问「今天要学什么」「今天的任务」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult listExamTasks(String date) {
        return ToolBusinessResult.success(examService.tasksText(requireCurrentUser(), date));
    }

    @Tool(value = "按备考计划生成今天的任务（每个科目一条）。用户说「帮我排今天的任务」「生成今天的计划」时调用；"
            + "今天已经有任务时不会重复生成。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult generateExamTasks() {
        String userId = requireCurrentUser();
        int created = examService.generateTodayTasks(userId);
        String head = created > 0 ? "已按计划生成 " + created + " 项今天任务。\n" : "今天已经有任务了，没有重复生成。\n";
        return ToolBusinessResult.success(head + examService.todayText(userId));
    }

    @Tool(value = "给某一天加一条自定义考研任务（不传 date 就是今天）。用户说「再加一条背单词 30 分钟」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult addExamTask(String subject, String content, Integer plannedMinutes, String date) {
        return ToolBusinessResult.success(examService.addTask(requireCurrentUser(), subject, content,
                plannedMinutes, date));
    }

    @Tool(value = "把考研任务标记成完成/跳过/未完成。优先传 taskId——listExamTasks 的列表里每个任务前面那个 #编号 就是 taskId"
            + "（不要拿 1、2 这种行号当 id；没给 id 时也可以传 keyword，按科目或内容片段在今天的任务里找）。"
            + "status 传 DONE/SKIPPED/PENDING（写成 完成/做完/跳过/未完成 也行；"
            + "不传就按「完成」处理，因为用户说「XX 做完了」时通常就是来勾掉的）。"
            + "用户说「数学那项做完了」「英语今天不做了」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult updateExamTask(Long taskId, String keyword, String status, String note) {
        return ToolBusinessResult.success(examService.updateTask(requireCurrentUser(), taskId, keyword, status, note));
    }

    @Tool(value = "考研打卡：记录今天学了多久（minutes，单位分钟）和一句备注。用户说「打卡」「今天学了 3 小时」时调用；"
            + "3 小时要换算成 180 分钟。返回连续打卡天数与今天的任务完成情况。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult examCheckin(Integer minutes, String note) {
        return ToolBusinessResult.success(examService.checkin(requireCurrentUser(), minutes, note));
    }

    @Tool(value = "看考研进度：今天的完成率与时长、近 7 天完成率、打卡天数、连续天数、最弱科目、遗留任务。"
            + "用户问「我进度怎么样」「这周完成得如何」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult examProgress() {
        return ToolBusinessResult.success(examService.progressText(requireCurrentUser()));
    }

    @Tool(value = "开启或关闭考研的自动推送（早计划 / 晚打卡提醒 / 周复盘）。用户说「别催我了」「继续监督我」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult setExamPush(Boolean enabled) {
        return ToolBusinessResult.success(examService.setEnabled(requireCurrentUser(), !Boolean.FALSE.equals(enabled)));
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
