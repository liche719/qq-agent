package com.liche.wechatagent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 考研规划模块的业务逻辑：备考计划、每日任务、打卡与进度统计。
 *
 * <p>设计原则和面试陪练一致：**数字由程序算，不靠模型记**。计划、任务、打卡都落库；
 * 推送文案里的完成率、连续天数、最弱科目全部由这里按数据算出来，模型只负责转述和催。
 */
@Service
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SUBJECTS = 8;
    private static final int MAX_TASK_CHARS = 300;
    private static final int MAX_NOTE_CHARS = 300;

    private final ExamPlanRepository plans;
    private final ExamTaskRepository tasks;
    private final ExamCheckinRepository checkins;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zone;

    public ExamService(ExamPlanRepository plans, ExamTaskRepository tasks, ExamCheckinRepository checkins,
                       @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.plans = plans;
        this.tasks = tasks;
        this.checkins = checkins;
        this.zone = parseZone(timeZone);
    }

    /** 计划里的一个科目 */
    public record Subject(String name, Integer targetScore, Integer dailyMinutes, String dailyPlan) {
    }

    public ExamPlan plan(String userId) {
        return userId == null || userId.isBlank() ? null : plans.findById(userId).orElse(null);
    }

    public boolean planEnabled(String userId) {
        ExamPlan plan = plan(userId);
        return plan != null && Boolean.TRUE.equals(plan.getEnabled());
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    // ==================== 计划 ====================

    /**
     * 新建/更新备考计划。{@code subjectsText} 用分号分隔科目，每个科目四个字段用冒号分隔：
     * {@code 数学:120:120:强化第3章;英语:70:60:阅读2篇+单词50}（只有科目名是必填，其它可留空）。
     */
    @Transactional
    public String savePlan(String userId, String examDate, String school, String major, String stage,
                           Integer dailyMinutes, String subjectsText, String remark) {
        if (userId == null || userId.isBlank()) {
            return "拿不到用户上下文，没保存。";
        }
        LocalDate parsedExamDate = parseDate(examDate);
        if (examDate != null && !examDate.isBlank() && parsedExamDate == null) {
            return "考试日期没看懂（要 2026-12-20 这种写法），计划没有保存。";
        }
        List<Subject> subjects = parseSubjects(subjectsText, dailyMinutes);
        if (subjects.isEmpty()) {
            return "至少要写一个科目（例如「数学:120:120:强化第3章;英语:70:60:阅读2篇」），计划没有保存。";
        }
        LocalDateTime now = LocalDateTime.now(zone);
        ExamPlan plan = plans.findById(userId).orElse(null);
        boolean created = plan == null;
        if (created) {
            plan = new ExamPlan();
            plan.setUserId(userId);
            plan.setCreatedAt(now);
        }
        if (parsedExamDate != null) {
            plan.setExamDate(parsedExamDate);
        }
        if (school != null && !school.isBlank()) {
            plan.setSchool(clip(school, 120));
        }
        if (major != null && !major.isBlank()) {
            plan.setMajor(clip(major, 120));
        }
        if (stage != null && !stage.isBlank()) {
            plan.setStage(normalizeStage(stage));
        }
        plan.setDailyMinutes(subjects.stream().mapToInt(s -> s.dailyMinutes() == null ? 0 : s.dailyMinutes()).sum() > 0
                ? subjects.stream().mapToInt(s -> s.dailyMinutes() == null ? 0 : s.dailyMinutes()).sum()
                : (dailyMinutes == null || dailyMinutes <= 0 ? null : dailyMinutes));
        plan.setSubjects(writeSubjects(subjects));
        if (remark != null && !remark.isBlank()) {
            plan.setRemark(clip(remark, 500));
        }
        plan.setEnabled(true);
        plan.setUpdatedAt(now);
        plans.save(plan);
        log.info("备考计划已保存 user={} created={} subjects={} examDate={}", userId, created, subjects.size(),
                plan.getExamDate());
        return (created ? "记下了你的备考计划。\n" : "计划已更新。\n") + planText(userId)
                + "\n\n（计划已保存。回复时把上面的计划复述一遍、问一句要不要现在排今天的任务即可；"
                + "不要在同一条回复里顺手生成任务、更不要把任何任务标成完成。）";
    }

    @Transactional
    public String setEnabled(String userId, boolean enabled) {
        ExamPlan plan = plan(userId);
        if (plan == null) {
            return "还没有备考计划，先说「考研 2026-12-20 目标院校 专业」之类的把计划建起来。";
        }
        plan.setEnabled(enabled);
        plan.setUpdatedAt(LocalDateTime.now(zone));
        plans.save(plan);
        return enabled ? "已开启考研推送（早计划 / 晚打卡 / 周复盘）。" : "已关闭考研推送，计划和任务都还在。";
    }

    public List<Subject> subjects(String userId) {
        ExamPlan plan = plan(userId);
        // 读库必须走 readSubjects（列里存的是 JSON）：用明文解析器会把整段 JSON 当成一个科目名，
        // 生成的每日任务标题就变成 [{"name"… 那种乱码（实测踩过一次）。
        return plan == null ? List.of() : readSubjects(plan.getSubjects(), plan.getDailyMinutes());
    }

    // ==================== 每日任务 ====================

    public List<ExamTask> todayTasks(String userId) {
        return tasks.findByUserIdAndPlanDateOrderBySortOrderAscIdAsc(userId, today());
    }

    public List<ExamTask> tasksOn(String userId, LocalDate date) {
        return tasks.findByUserIdAndPlanDateOrderBySortOrderAscIdAsc(userId, date);
    }

    /** 按计划生成今天的任务；今天已经有任务就不重复生成（返回 0）。 */
    @Transactional
    public int generateTodayTasks(String userId) {
        LocalDate date = today();
        if (tasks.countByUserIdAndPlanDate(userId, date) > 0) {
            return 0;
        }
        List<Subject> subjects = subjects(userId);
        if (subjects.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(zone);
        int order = 0;
        for (Subject subject : subjects) {
            ExamTask task = new ExamTask();
            task.setUserId(userId);
            task.setPlanDate(date);
            task.setSubject(clip(subject.name(), 60));
            task.setContent(clip(subject.dailyPlan() == null || subject.dailyPlan().isBlank()
                    ? "推进「" + subject.name() + "」" : subject.dailyPlan(), MAX_TASK_CHARS));
            task.setPlannedMinutes(subject.dailyMinutes());
            task.setStatus(ExamTask.STATUS_PENDING);
            task.setSource(ExamTask.SOURCE_PLAN);
            task.setSortOrder(order++);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            tasks.save(task);
        }
        log.info("生成今日考研任务 user={} date={} count={}", userId, date, order);
        return order;
    }

    @Transactional
    public String addTask(String userId, String subject, String content, Integer plannedMinutes, String dateText) {
        if (content == null || content.isBlank()) {
            return "任务内容不能为空。";
        }
        LocalDate date = parseDate(dateText);
        if (date == null) {
            date = today();
        }
        LocalDateTime now = LocalDateTime.now(zone);
        ExamTask task = new ExamTask();
        task.setUserId(userId);
        task.setPlanDate(date);
        task.setSubject(clip(subject == null || subject.isBlank() ? "自定义" : subject, 60));
        task.setContent(clip(content, MAX_TASK_CHARS));
        task.setPlannedMinutes(plannedMinutes == null || plannedMinutes <= 0 ? null : Math.min(plannedMinutes, 1440));
        task.setStatus(ExamTask.STATUS_PENDING);
        task.setSource(ExamTask.SOURCE_MANUAL);
        task.setSortOrder((int) tasks.countByUserIdAndPlanDate(userId, date));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.save(task);
        return "已加进 " + date.format(DATE) + " 的任务：" + task.getSubject() + " · " + task.getContent()
                + (task.getPlannedMinutes() == null ? "" : "（约 " + task.getPlannedMinutes() + " 分钟）");
    }

    /**
     * 改任务状态。{@code taskId} 优先；没给 id 时用关键词在**今天的任务**里模糊匹配
     * （模型经常只记得内容，不记得 id）。
     */
    @Transactional
    public String updateTask(String userId, Long taskId, String keyword, String status, String note) {
        ExamTask task = taskId == null ? matchToday(userId, keyword) : tasks.findById(taskId).orElse(null);
        if (task == null || !userId.equals(task.getUserId())) {
            return taskId == null
                    ? "没在今天的任务里找到「" + (keyword == null ? "" : keyword) + "」，可以先让我列出今天的任务。"
                    : "没找到这个任务（id 可能不对）。";
        }
        String normalized = normalizeStatus(status);
        if (normalized == null) {
            return "状态只认 DONE（完成）/ SKIPPED（跳过）/ PENDING（改回未完成）。";
        }
        task.setStatus(normalized);
        task.setDoneAt(ExamTask.STATUS_DONE.equals(normalized) ? LocalDateTime.now(zone) : null);
        if (note != null && !note.isBlank()) {
            task.setNote(clip(note, MAX_NOTE_CHARS));
        }
        task.setUpdatedAt(LocalDateTime.now(zone));
        tasks.save(task);
        String label = ExamTask.STATUS_DONE.equals(normalized) ? "已完成 ✅"
                : ExamTask.STATUS_SKIPPED.equals(normalized) ? "已跳过 ⏭" : "改回未完成";
        return "「" + task.getSubject() + " · " + task.getContent() + "」" + label;
    }

    private ExamTask matchToday(String userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String needle = keyword.trim().toLowerCase();
        return todayTasks(userId).stream()
                .filter(task -> contains(task.getSubject(), needle) || contains(task.getContent(), needle))
                .findFirst()
                .orElse(null);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    // ==================== 打卡与统计 ====================

    /** 打卡：一天一条，重复打卡视为更新。 */
    @Transactional
    public String checkin(String userId, Integer minutes, String note) {
        LocalDate date = today();
        List<ExamTask> todayTasks = tasksOn(userId, date);
        int total = todayTasks.size();
        int done = (int) todayTasks.stream().filter(t -> ExamTask.STATUS_DONE.equals(t.getStatus())).count();
        ExamCheckin checkin = checkins.findByUserIdAndCheckinDate(userId, date).orElse(null);
        boolean created = checkin == null;
        LocalDateTime now = LocalDateTime.now(zone);
        if (created) {
            checkin = new ExamCheckin();
            checkin.setUserId(userId);
            checkin.setCheckinDate(date);
            checkin.setCreatedAt(now);
        }
        if (minutes != null && minutes > 0) {
            checkin.setMinutes(Math.min(minutes, 24 * 60));
        }
        if (note != null && !note.isBlank()) {
            checkin.setNote(clip(note, 500));
        }
        checkin.setTasksTotal(total);
        checkin.setTasksDone(done);
        checkin.setUpdatedAt(now);
        checkins.save(checkin);

        StringBuilder sb = new StringBuilder("已打卡（" + date.format(DATE) + "）");
        if (checkin.getMinutes() != null) {
            sb.append("：今天学了约 ").append(checkin.getMinutes()).append(" 分钟");
        }
        if (total > 0) {
            sb.append("，任务 ").append(done).append("/").append(total);
        }
        sb.append("。连续打卡 ").append(streak(userId)).append(" 天。");
        if (total > 0 && done < total) {
            sb.append("\n还差 ").append(total - done).append(" 项，睡前再收个尾？");
        } else if (total > 0) {
            sb.append("\n今天的任务清空了，稳。");
        }
        if (!created) {
            sb.append("（今天之前打过卡，这条是更新）");
        }
        return sb.toString();
    }

    /** 连续打卡天数：从今天（没打就从昨天）往前数。 */
    public int streak(String userId) {
        LocalDate cursor = today();
        if (checkins.findByUserIdAndCheckinDate(userId, cursor).isEmpty()) {
            cursor = cursor.minusDays(1);
        }
        int days = 0;
        while (days < 3650 && checkins.findByUserIdAndCheckinDate(userId, cursor).isPresent()) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    public Map<String, Object> stats(String userId) {
        LocalDate date = today();
        List<ExamTask> todayTasks = tasksOn(userId, date);
        int total = todayTasks.size();
        int done = (int) todayTasks.stream().filter(t -> ExamTask.STATUS_DONE.equals(t.getStatus())).count();
        int skipped = (int) todayTasks.stream().filter(t -> ExamTask.STATUS_SKIPPED.equals(t.getStatus())).count();
        LocalDate from = date.minusDays(6);
        List<ExamTask> week = tasks.findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(userId, from, date);
        int weekTotal = week.size();
        int weekDone = (int) week.stream().filter(t -> ExamTask.STATUS_DONE.equals(t.getStatus())).count();
        List<ExamCheckin> weekCheckins = checkins.findByUserIdAndCheckinDateBetweenOrderByCheckinDateAsc(userId, from, date);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("date", date.toString());
        stats.put("todayTotal", total);
        stats.put("todayDone", done);
        stats.put("todaySkipped", skipped);
        stats.put("todayPercent", total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
        stats.put("todayMinutes", todayTasks.stream().filter(t -> ExamTask.STATUS_DONE.equals(t.getStatus()))
                .mapToInt(t -> t.getPlannedMinutes() == null ? 0 : t.getPlannedMinutes()).sum());
        stats.put("weekTotal", weekTotal);
        stats.put("weekDone", weekDone);
        stats.put("weekPercent", weekTotal == 0 ? 0 : (int) Math.round(weekDone * 100.0 / weekTotal));
        stats.put("weekCheckinDays", weekCheckins.size());
        stats.put("streak", streak(userId));
        stats.put("checkedInToday", checkins.findByUserIdAndCheckinDate(userId, date).isPresent());
        stats.put("weakestSubject", weakestSubject(week));
        stats.put("pendingOverdue", pendingOverdue(userId, date));
        return stats;
    }

    /** 近 N 天的完成率（面板柱状图用）。 */
    public List<Map<String, Object>> trend(String userId, int days) {
        int span = Math.max(2, Math.min(days, 60));
        LocalDate to = today();
        LocalDate from = to.minusDays(span - 1L);
        List<ExamTask> all = tasks.findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(userId, from, to);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            LocalDate day = from.plusDays(i);
            List<ExamTask> ofDay = all.stream().filter(t -> day.equals(t.getPlanDate())).toList();
            int total = ofDay.size();
            int done = (int) ofDay.stream().filter(t -> ExamTask.STATUS_DONE.equals(t.getStatus())).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", day.format(DATE));
            item.put("value", total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
            item.put("total", total);
            item.put("done", done);
            items.add(item);
        }
        return items;
    }

    private String weakestSubject(List<ExamTask> week) {
        Map<String, int[]> bySubject = new LinkedHashMap<>();
        for (ExamTask task : week) {
            String key = task.getSubject() == null || task.getSubject().isBlank() ? "其它" : task.getSubject();
            int[] counters = bySubject.computeIfAbsent(key, k -> new int[2]);
            counters[1]++;
            if (ExamTask.STATUS_DONE.equals(task.getStatus())) {
                counters[0]++;
            }
        }
        return bySubject.entrySet().stream()
                .filter(entry -> entry.getValue()[1] >= 2)
                .min(Comparator.comparingDouble(entry -> entry.getValue()[0] * 1.0 / entry.getValue()[1]))
                .map(entry -> entry.getKey() + "（" + entry.getValue()[0] + "/" + entry.getValue()[1] + "）")
                .orElse(null);
    }

    private int pendingOverdue(String userId, LocalDate date) {
        return tasks.findByUserIdAndStatusOrderByPlanDateAscIdAsc(userId, ExamTask.STATUS_PENDING).stream()
                .filter(task -> task.getPlanDate() != null && task.getPlanDate().isBefore(date))
                .toList().size();
    }

    // ==================== 文案 ====================

    public String planText(String userId) {
        ExamPlan plan = plan(userId);
        if (plan == null) {
            return "还没有备考计划。可以这样告诉我：「考研 2026-12-20 报考 XX 大学 计算机，科目 数学:120:120:强化第3章;英语:70:60:阅读2篇+单词50;政治:70:60:刷题;专业课:110:120:真题」。";
        }
        StringBuilder sb = new StringBuilder("📚 备考计划\n");
        if (plan.getSchool() != null || plan.getMajor() != null) {
            sb.append("目标：").append(plan.getSchool() == null ? "—" : plan.getSchool())
                    .append(" ").append(plan.getMajor() == null ? "" : plan.getMajor()).append("\n");
        }
        if (plan.getExamDate() != null) {
            long days = ChronoUnit.DAYS.between(today(), plan.getExamDate());
            sb.append("考试日期：").append(plan.getExamDate().format(FULL))
                    .append(days >= 0 ? "（还有 " + days + " 天）" : "（已过）").append("\n");
        }
        sb.append("阶段：").append(stageText(plan.getStage()))
                .append("　每天计划：").append(plan.getDailyMinutes() == null ? "—" : plan.getDailyMinutes() + " 分钟")
                .append("\n");
        List<Subject> subjects = subjects(userId);
        if (!subjects.isEmpty()) {
            sb.append("科目：\n");
            for (Subject subject : subjects) {
                sb.append("· ").append(subject.name());
                if (subject.targetScore() != null) {
                    sb.append("（目标 ").append(subject.targetScore()).append(" 分）");
                }
                if (subject.dailyMinutes() != null) {
                    sb.append(" 每天 ").append(subject.dailyMinutes()).append(" 分钟");
                }
                if (subject.dailyPlan() != null && !subject.dailyPlan().isBlank()) {
                    sb.append("：").append(subject.dailyPlan());
                }
                sb.append("\n");
            }
        }
        sb.append("推送：").append(Boolean.TRUE.equals(plan.getEnabled()) ? "已开启（早计划 / 晚打卡 / 周复盘）" : "已关闭");
        if (plan.getRemark() != null && !plan.getRemark().isBlank()) {
            sb.append("\n备注：").append(plan.getRemark());
        }
        return sb.toString();
    }

    public String todayText(String userId) {
        return renderTasks(userId, today(), today().format(DATE));
    }

    /** 任意一天的任务清单（{@code dateText} 认不出来或就是今天时按今天渲染）。 */
    public String tasksText(String userId, String dateText) {
        LocalDate date = parseDate(dateText);
        if (date == null || date.equals(today())) {
            return todayText(userId);
        }
        List<ExamTask> list = tasksOn(userId, date);
        if (list.isEmpty()) {
            return date.format(DATE) + " 那天没有任务记录。";
        }
        return renderTasks(userId, date, date.format(DATE));
    }

    private String renderTasks(String userId, LocalDate date, String label) {
        List<ExamTask> todayTasks = tasksOn(userId, date);
        if (todayTasks.isEmpty()) {
            ExamPlan plan = plan(userId);
            return plan == null
                    ? date.format(DATE) + " 还没有任务，也还没有备考计划——先说目标院校和专业，我给你排。"
                    : date.format(DATE) + " 还没有具体任务。说一句「生成今天的考研任务」我就按计划排（"
                    + subjects(userId).size() + " 个科目）。";
        }
        StringBuilder sb = new StringBuilder("📌 " + label + " 的任务\n");
        int done = 0;
        int index = 1;
        for (ExamTask task : todayTasks) {
            boolean isDone = ExamTask.STATUS_DONE.equals(task.getStatus());
            boolean skipped = ExamTask.STATUS_SKIPPED.equals(task.getStatus());
            if (isDone) {
                done++;
            }
            sb.append(index++).append(". ")
                    .append(isDone ? "✅" : skipped ? "⏭" : "⬜").append(" ")
                    .append(task.getSubject() == null ? "" : task.getSubject() + " · ")
                    .append(task.getContent());
            if (task.getPlannedMinutes() != null) {
                sb.append("（").append(task.getPlannedMinutes()).append(" 分钟）");
            }
            if (task.getNote() != null && !task.getNote().isBlank()) {
                sb.append("｜").append(task.getNote());
            }
            sb.append("\n");
        }
        sb.append("完成 ").append(done).append("/").append(todayTasks.size())
                .append("（").append(Math.round(done * 100.0 / todayTasks.size())).append("%）");
        sb.append("\n做完了说一句「XX 做完了」，我就给你划掉。");
        return sb.toString();
    }

    public String progressText(String userId) {
        ExamPlan plan = plan(userId);
        if (plan == null) {
            return "还没有备考计划，先告诉我目标院校、专业和考试日期。";
        }
        Map<String, Object> stats = stats(userId);
        StringBuilder sb = new StringBuilder("📈 考研进度\n");
        if (plan.getExamDate() != null) {
            long days = ChronoUnit.DAYS.between(today(), plan.getExamDate());
            sb.append("距考试 ").append(days >= 0 ? days + " 天" : "已过 " + (-days) + " 天").append("\n");
        }
        sb.append("今天：").append(stats.get("todayDone")).append("/").append(stats.get("todayTotal"))
                .append("（").append(stats.get("todayPercent")).append("%）")
                .append("，完成时长约 ").append(stats.get("todayMinutes")).append(" 分钟\n");
        sb.append("近 7 天：").append(stats.get("weekDone")).append("/").append(stats.get("weekTotal"))
                .append("（").append(stats.get("weekPercent")).append("%），打卡 ")
                .append(stats.get("weekCheckinDays")).append(" 天\n");
        sb.append("连续打卡：").append(stats.get("streak")).append(" 天");
        if (stats.get("weakestSubject") != null) {
            sb.append("　最弱科目：").append(stats.get("weakestSubject"));
        }
        if (((Number) stats.get("pendingOverdue")).intValue() > 0) {
            sb.append("\n⚠️ 有 ").append(stats.get("pendingOverdue")).append(" 项以前的任务还没完成，要么补上要么让我跳过。");
        }
        return sb.toString();
    }

    /** 早推送：今天的计划 + 倒计时 + 一句催。 */
    public String morningText(String userId) {
        ExamPlan plan = plan(userId);
        int created = generateTodayTasks(userId);
        StringBuilder sb = new StringBuilder("☀️ ").append(today().format(DATE)).append(" 计划");
        if (plan != null && plan.getExamDate() != null) {
            long days = ChronoUnit.DAYS.between(today(), plan.getExamDate());
            sb.append("（距考试 ").append(Math.max(0, days)).append(" 天）");
        }
        sb.append("\n");
        List<ExamTask> todayTasks = todayTasks(userId);
        if (todayTasks.isEmpty()) {
            return sb.append("今天还没有任务，先把计划补齐（说「考研计划」看看现状）。").toString();
        }
        for (ExamTask task : todayTasks) {
            sb.append("⬜ ").append(task.getSubject() == null ? "" : task.getSubject() + " · ")
                    .append(task.getContent());
            if (task.getPlannedMinutes() != null) {
                sb.append("（").append(task.getPlannedMinutes()).append(" 分钟）");
            }
            sb.append("\n");
        }
        int total = todayTasks.size();
        int minutes = todayTasks.stream().mapToInt(t -> t.getPlannedMinutes() == null ? 0 : t.getPlannedMinutes()).sum();
        sb.append("合计约 ").append(minutes).append(" 分钟 / ").append(total).append(" 项");
        if (created > 0) {
            sb.append("\n（这是按你的计划新生成的）");
        }
        int overdue = pendingOverdue(userId, today());
        if (overdue > 0) {
            sb.append("\n另外还有 ").append(overdue).append(" 项旧任务没勾掉，今天顺手清一下。");
        }
        return sb.toString();
    }

    /** 晚推送：今天完成情况 + 未完成清单 + 打卡引导。 */
    public String eveningText(String userId) {
        Map<String, Object> stats = stats(userId);
        int total = ((Number) stats.get("todayTotal")).intValue();
        int done = ((Number) stats.get("todayDone")).intValue();
        StringBuilder sb = new StringBuilder("🌙 ").append(today().format(DATE)).append(" 收尾\n");
        if (total == 0) {
            sb.append("今天没有任务记录。明天想让我排的话，说一句「生成今天的考研任务」。");
            return sb.toString();
        }
        sb.append("完成 ").append(done).append("/").append(total).append("（").append(stats.get("todayPercent")).append("%）\n");
        List<String> pending = todayTasks(userId).stream()
                .filter(task -> ExamTask.STATUS_PENDING.equals(task.getStatus()))
                .map(task -> (task.getSubject() == null ? "" : task.getSubject() + " · ") + task.getContent())
                .limit(6)
                .toList();
        if (pending.isEmpty()) {
            sb.append("任务清空了，今天很稳 🎉\n");
        } else {
            sb.append("还差 ").append(pending.size()).append(" 项：\n");
            pending.forEach(item -> sb.append("⬜ ").append(item).append("\n"));
        }
        sb.append("今天学了多久？回一句「打卡 150」我就记上（连着 ").append(stats.get("streak")).append(" 天了）。");
        return sb.toString();
    }

    /** 周复盘：程序算出来的数字 + 下周重点。 */
    public String weeklyText(String userId) {
        Map<String, Object> stats = stats(userId);
        List<Map<String, Object>> trend = trend(userId, 7);
        StringBuilder sb = new StringBuilder("📊 本周复盘（").append(trend.isEmpty() ? "—" : trend.get(0).get("label"))
                .append(" ~ ").append(trend.isEmpty() ? "—" : trend.get(trend.size() - 1).get("label")).append("）\n");
        sb.append("任务 ").append(stats.get("weekDone")).append("/").append(stats.get("weekTotal"))
                .append("（").append(stats.get("weekPercent")).append("%），打卡 ").append(stats.get("weekCheckinDays"))
                .append(" 天，连续 ").append(stats.get("streak")).append(" 天\n");
        sb.append("每日完成率：");
        sb.append(trend.stream().map(item -> item.get("label") + " " + item.get("value") + "%")
                .reduce((a, b) -> a + "　" + b).orElse("—"));
        sb.append("\n");
        int weekPercent = ((Number) stats.get("weekPercent")).intValue();
        if (stats.get("weakestSubject") != null) {
            sb.append("最弱科目：").append(stats.get("weakestSubject")).append("，下周给它多留一个固定时段。\n");
        }
        int overdue = pendingOverdue(userId, today());
        if (overdue > 0) {
            sb.append("有 ").append(overdue).append(" 项遗留任务，别装作没看见——补掉或者明确跳过。\n");
        }
        sb.append(weekPercent >= 80 ? "这周执行力在线，保持节奏就行。"
                : weekPercent >= 50 ? "这周勉强及格，下周把最弱的那科提前到早上。"
                : "这周偏差有点大。下周只定三件必须完成的事，先连续七天打卡再说。");
        return sb.toString();
    }

    public String stageText(String stage) {
        if (ExamPlan.STAGE_INTENSIVE.equals(stage)) {
            return "强化";
        }
        if (ExamPlan.STAGE_SPRINT.equals(stage)) {
            return "冲刺";
        }
        return "基础";
    }

    public String stageCode(String text) {
        return normalizeStage(text);
    }

    // ==================== 解析工具 ====================

    private String normalizeStage(String stage) {
        if (stage == null) {
            return ExamPlan.STAGE_BASIC;
        }
        String value = stage.trim();
        if (value.contains("强化") || "INTENSIVE".equalsIgnoreCase(value)) {
            return ExamPlan.STAGE_INTENSIVE;
        }
        if (value.contains("冲刺") || "SPRINT".equalsIgnoreCase(value)) {
            return ExamPlan.STAGE_SPRINT;
        }
        if (value.contains("基础") || "BASIC".equalsIgnoreCase(value)) {
            return ExamPlan.STAGE_BASIC;
        }
        return ExamPlan.STAGE_BASIC;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (value.contains("DONE") || value.contains("完成") || value.contains("做完") || value.equals("OK")) {
            return ExamTask.STATUS_DONE;
        }
        if (value.contains("SKIP") || value.contains("跳过") || value.contains("不做")) {
            return ExamTask.STATUS_SKIPPED;
        }
        if (value.contains("PENDING") || value.contains("未完成") || value.contains("没做") || value.contains("重置")) {
            return ExamTask.STATUS_PENDING;
        }
        return null;
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace('/', '-').replace('.', '-');
        if (value.contains("今天")) {
            return today();
        }
        if (value.contains("明天")) {
            return today().plusDays(1);
        }
        if (value.contains("昨天")) {
            return today().minusDays(1);
        }
        try {
            String[] parts = value.split("-");
            if (parts.length == 3) {
                return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            if (parts.length == 2) {
                return LocalDate.of(today().getYear(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            if (parts.length == 1 && parts[0].length() == 8) {
                return LocalDate.of(Integer.parseInt(parts[0].substring(0, 4)),
                        Integer.parseInt(parts[0].substring(4, 6)), Integer.parseInt(parts[0].substring(6, 8)));
            }
        } catch (RuntimeException ignored) {
            // 日期写得不规范，交给调用方提示
        }
        return null;
    }

    /** 解析科目串：{@code 数学:120:120:强化第3章;英语:70:60:阅读2篇} */
    List<Subject> parseSubjects(String text, Integer fallbackMinutes) {
        List<Subject> subjects = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return subjects;
        }
        for (String raw : text.split("[;；\\n]")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            String[] fields = part.split("[:：]", 4);
            String name = fields[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            Integer targetScore = intOrNull(fields.length > 1 ? fields[1] : null, 0, 500);
            Integer minutes = intOrNull(fields.length > 2 ? fields[2] : null, 0, 24 * 60);
            String dailyPlan = fields.length > 3 ? fields[3].trim() : "";
            if (minutes == null && fallbackMinutes != null && fallbackMinutes > 0) {
                minutes = Math.max(10, fallbackMinutes / Math.max(1, countSubjects(text)));
            }
            subjects.add(new Subject(clip(name, 60), targetScore, minutes, clip(dailyPlan, 200)));
            if (subjects.size() >= MAX_SUBJECTS) {
                break;
            }
        }
        return subjects;
    }

    private int countSubjects(String text) {
        int count = 0;
        for (String raw : text.split("[;；\\n]")) {
            if (!raw.trim().isEmpty()) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private Integer intOrNull(String text, int min, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(text.trim().replaceAll("[^0-9-]", ""));
            return value < min || value > max ? null : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String writeSubjects(List<Subject> subjects) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Subject subject : subjects) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", subject.name());
                item.put("targetScore", subject.targetScore());
                item.put("dailyMinutes", subject.dailyMinutes());
                item.put("dailyPlan", subject.dailyPlan());
                raw.add(item);
            }
            return clip(mapper.writeValueAsString(raw), 2000);
        } catch (Exception exception) {
            log.warn("科目 JSON 序列化失败：{}", exception.toString());
            return "[]";
        }
    }

    /** 从库里存的 JSON 读科目（兼容有人手工塞进去的明文） */
    @SuppressWarnings("unchecked")
    private List<Subject> readSubjects(String json, Integer fallbackMinutes) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[")) {
            // 兼容手工塞进来的「数学:120:120」明文
            return parseSubjects(trimmed, fallbackMinutes);
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(trimmed, List.class);
            List<Subject> subjects = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                Object name = item.get("name");
                if (name == null || String.valueOf(name).isBlank()) {
                    continue;
                }
                subjects.add(new Subject(clip(String.valueOf(name), 60),
                        asInt(item.get("targetScore")), asInt(item.get("dailyMinutes")),
                        clip(item.get("dailyPlan") == null ? "" : String.valueOf(item.get("dailyPlan")), 200)));
            }
            return subjects;
        } catch (Exception exception) {
            log.warn("科目 JSON 解析失败，按明文处理：{}", exception.toString());
            return parseSubjects(trimmed, fallbackMinutes);
        }
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : intOrNull(String.valueOf(value), 0, 24 * 60);
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    /** 计划是否需要今天的早推送 */
    public boolean morningDue(ExamPlan plan, LocalDate date) {
        return plan != null && !date.equals(plan.getLastMorningPush());
    }

    public boolean eveningDue(ExamPlan plan, LocalDate date) {
        return plan != null && !date.equals(plan.getLastEveningPush());
    }

    public boolean weeklyDue(ExamPlan plan, LocalDate date) {
        return plan != null && !date.equals(plan.getLastWeeklyPush());
    }

    @Transactional
    public void markPushed(String userId, String kind, LocalDate date) {
        ExamPlan plan = plan(userId);
        if (plan == null) {
            return;
        }
        if ("morning".equals(kind)) {
            plan.setLastMorningPush(date);
        } else if ("evening".equals(kind)) {
            plan.setLastEveningPush(date);
        } else if ("weekly".equals(kind)) {
            plan.setLastWeeklyPush(date);
        }
        plan.setUpdatedAt(LocalDateTime.now(zone));
        plans.save(plan);
    }

    /** 面板「备考计划」区块 */
    public Map<String, Object> planPanel(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ExamPlan plan = plan(userId);
        if (plan == null) {
            rows.add(row("状态", "还没有备考计划"));
            rows.add(row("怎么建", "在 QQ 里说：考研 2026-12-20 报考XX大学 计算机，科目 数学:120:120:强化第3章"));
            return panel(rows);
        }
        rows.add(row("目标院校", join(plan.getSchool(), plan.getMajor())));
        if (plan.getExamDate() != null) {
            long days = ChronoUnit.DAYS.between(today(), plan.getExamDate());
            rows.add(row("考试日期", plan.getExamDate().format(FULL) + "（" + (days >= 0 ? "还有 " + days + " 天" : "已过") + "）"));
        }
        rows.add(row("阶段", stageText(plan.getStage())));
        rows.add(row("每天计划", plan.getDailyMinutes() == null ? "—" : plan.getDailyMinutes() + " 分钟"));
        rows.add(row("推送", Boolean.TRUE.equals(plan.getEnabled()) ? "已开启" : "已关闭"));
        rows.add(row("连续打卡", streak(userId) + " 天"));
        if (plan.getRemark() != null && !plan.getRemark().isBlank()) {
            rows.add(row("备注", plan.getRemark()));
        }
        for (Subject subject : subjects(userId)) {
            rows.add(row(subject.name(), subjectText(subject)));
        }
        rows.add(row("上次推送", "早 " + orDash(plan.getLastMorningPush()) + "　晚 " + orDash(plan.getLastEveningPush())
                + "　周 " + orDash(plan.getLastWeeklyPush())));
        rows.add(row("最近更新", plan.getUpdatedAt() == null ? "—" : plan.getUpdatedAt().format(STAMP)));
        return panel(rows);
    }

    private String subjectText(Subject subject) {
        StringBuilder sb = new StringBuilder();
        if (subject.targetScore() != null) {
            sb.append("目标 ").append(subject.targetScore()).append(" 分");
        }
        if (subject.dailyMinutes() != null) {
            sb.append(sb.isEmpty() ? "" : "　").append("每天 ").append(subject.dailyMinutes()).append(" 分钟");
        }
        if (subject.dailyPlan() != null && !subject.dailyPlan().isBlank()) {
            sb.append(sb.isEmpty() ? "" : "　").append(subject.dailyPlan());
        }
        return sb.isEmpty() ? "—" : sb.toString();
    }

    /** 面板「今日任务」表格（状态是原值，中文由前端描述里的 tag 映射翻） */
    public Map<String, Object> tasksPanel(String userId, String dateText) {
        LocalDate date = parseDate(dateText);
        if (date == null) {
            date = today();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExamTask task : tasksOn(userId, date)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("subject", task.getSubject());
            item.put("content", task.getContent());
            item.put("status", task.getStatus());
            item.put("plannedMinutes", task.getPlannedMinutes() == null ? "" : task.getPlannedMinutes());
            item.put("doneAt", task.getDoneAt() == null ? "" : task.getDoneAt().format(STAMP));
            item.put("note", task.getNote() == null ? "" : task.getNote());
            rows.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("date", date.toString());
        return result;
    }

    public Map<String, Object> trendPanel(String userId, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", trend(userId, days));
        result.put("unit", "%");
        return result;
    }

    /** 面板「打卡记录」表格 */
    public Map<String, Object> checkinPanel(String userId, int days) {
        int span = Math.max(2, Math.min(days, 60));
        LocalDate to = today();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExamCheckin checkin : checkins.findByUserIdAndCheckinDateBetweenOrderByCheckinDateAsc(userId,
                to.minusDays(span - 1L), to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", checkin.getCheckinDate() == null ? "" : checkin.getCheckinDate().format(FULL));
            item.put("minutes", checkin.getMinutes() == null ? "" : checkin.getMinutes());
            item.put("tasks", (checkin.getTasksDone() == null ? 0 : checkin.getTasksDone()) + "/"
                    + (checkin.getTasksTotal() == null ? 0 : checkin.getTasksTotal()));
            item.put("note", checkin.getNote() == null ? "" : checkin.getNote());
            rows.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> panel(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value == null ? "—" : value);
        return item;
    }

    private String join(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        String joined = (a + " " + b).trim();
        return joined.isEmpty() ? "—" : joined;
    }

    private String orDash(LocalDate date) {
        return date == null ? "—" : date.format(DATE);
    }

    private static ZoneId parseZone(String value) {
        try {
            return value == null || value.isBlank() ? DEFAULT_ZONE : ZoneId.of(value.trim());
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    public Optional<ExamPlan> findPlan(String userId) {
        return Optional.ofNullable(plan(userId));
    }
}
