package com.liche.wechatagent.tool;

import com.liche.wechatagent.exam.ExamService;
import com.liche.wechatagent.exam.ExamTrackService;
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
    private final ExamTrackService examTrackService;
    private final ToolStatusService statusService;

    public ExamTool(ExamService examService, ExamTrackService examTrackService, ToolStatusService statusService) {
        this.examService = examService;
        this.examTrackService = examTrackService;
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
            + "stage 传 基础/强化/冲刺。用户第一次说考研目标、或要求改计划时调用。"
            + "只想改其中某一项（例如「考试日期改成 2027-12-25」）时，**subjects 可以不传、原科目会保留**；"
            + "但只要传了 subjects，就会整体替换掉原来的科目，所以传就必须把全部科目一次列全（先调 viewExamPlan 看现状）。")
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

    // ==================== 章节/轮次进度 ====================

    @Tool(value = "记录或更新一个复习单元的进度（章节/轮次）。例如「数学二高数第三章 120 题做到 40 题」→ "
            + "subject=高数, phase=基础, title=第三章, total=120, done=40, unit=题；「408 数据结构王道 8 章做了 3 章」→ "
            + "subject=数据结构, group=408, title=王道一轮, total=8, done=3, unit=章。"
            + "group 不传就按科目名自动归组（数据结构/组成/操作系统/网络→408，高数/线代→数学，单词/阅读→英语…）。"
            + "dueDate 是打算哪天做完（YYYY-MM-DD，可不传）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult saveExamProgress(String subject, String group, String phase, String title,
                                               Integer total, Integer done, String unit, String dueDate, String note) {
        return ToolBusinessResult.success(examTrackService.saveProgress(requireCurrentUser(), subject, group, phase,
                title, total, done, unit, dueDate, note));
    }

    @Tool(value = "推进某条进度的完成量：delta 传增量（例如做了 5 题传 5），或者 done 直接传新的完成量"
            + "（「这一章做完了」就传 done=total）。id 从 viewExamProgress 的结果里拿。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult updateExamProgress(Long id, Integer delta, Integer done) {
        return ToolBusinessResult.success(examTrackService.bumpProgress(requireCurrentUser(), id, delta, done));
    }

    @Tool(value = "查看复习进度：按科目/科目组列出每个单元的完成量与百分比、超期未完成的单元。"
            + "用户问「我复习到哪了」「进度怎么样」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult viewExamProgress() {
        return ToolBusinessResult.success(examTrackService.progressText(requireCurrentUser()));
    }

    // ==================== 错题回收 ====================

    @Tool(value = "记一条错题或顽固知识点，之后按 1/3/7/15/30 天自动抽你复习。用户说「这题我错了」「记个错题：快排最坏复杂度推导」"
            + "或把某道题的错因讲给你听时调用。title 是题目/知识点的简短摘要，detail 记错在哪、正确思路，source 记来源（660/王道/真题2015）。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult addExamMistake(String subject, String title, String detail, String source) {
        return ToolBusinessResult.success(examTrackService.addMistake(requireCurrentUser(), subject, title, detail, source));
    }

    @Tool(value = "记录一条错题的复习结果：result 传 RIGHT（做对了，往后推一个间隔）或 WRONG（又错了，回到第一天）。"
            + "用户说「错题 #3 记得」「那道快排的又错了」时调用；id 从 viewExamMistakes 里拿。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult reviewExamMistake(Long id, String result) {
        return ToolBusinessResult.success(examTrackService.reviewMistake(requireCurrentUser(), id, result));
    }

    @Tool(value = "查看错题本：今天到期要复习的、之后要复习的、已掌握的条数。用户问「错题本」「今天要复习什么」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult viewExamMistakes() {
        return ToolBusinessResult.success(examTrackService.mistakesText(requireCurrentUser()));
    }

    // ==================== 阶段里程碑 ====================

    @Tool(value = "设置一个阶段里程碑（带截止日的检查点），例如「基础一轮 2027-03-31」「408 一轮 2027-06-30」"
            + "「真题一遍 2027-11-30」。用户定目标、说某个阶段什么时候之前要完成时调用；超期未完成会在推送里点名。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult saveExamMilestone(String name, String dueDate, String note) {
        return ToolBusinessResult.success(examTrackService.saveMilestone(requireCurrentUser(), name, dueDate, note));
    }

    @Tool(value = "把里程碑标记成完成（done=true）或改回未完成（done=false）。id 从 viewExamMilestones 里拿。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult completeExamMilestone(Long id, Boolean done) {
        return ToolBusinessResult.success(examTrackService.completeMilestone(requireCurrentUser(), id,
                !Boolean.FALSE.equals(done)));
    }

    @Tool(value = "查看阶段里程碑：名字、截止日、剩余天数、是否超期。用户问「我的阶段目标」「里程碑」时调用。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult viewExamMilestones() {
        return ToolBusinessResult.success(examTrackService.milestonesText(requireCurrentUser()));
    }

    // ==================== 学习计时 ====================

    @Tool(value = "开始一段学习计时（用户说「开始学数学」「我开始刷 408 了」时调用）。subject 传学什么，例如「数学二 高数」。"
            + "结束时要调 endExamStudy 把这段时长记进当天打卡。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult startExamStudy(String subject) {
        return ToolBusinessResult.success(examTrackService.startStudy(requireCurrentUser(), subject));
    }

    @Tool(value = "结束学习计时，把这一段的分钟数记进当天打卡（并返回今天累计与连续天数）。用户说「结束学习」「学完了」时调用。"
            + "没有在计时时会告知无法记录，此时可以改用 examCheckin 让用户直接报时长。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, retryable = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult endExamStudy() {
        String userId = requireCurrentUser();
        ExamTrackService.StudyStop stop = examTrackService.stopStudy(userId);
        if (stop == null) {
            return ToolBusinessResult.success("现在没有在计时的学习段。让用户说「打卡 时长」直接记分钟，或者说「开始学XX」开始计时。");
        }
        String checkin = examService.checkin(userId, (int) stop.minutes(), "计时：" + stop.subject());
        return ToolBusinessResult.success("⏱「" + stop.subject() + "」记了 " + stop.minutes() + " 分钟。\n" + checkin);
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
