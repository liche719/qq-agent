package com.liche.wechatagent.exam;

import com.liche.wechatagent.maimemo.MaimemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 考研的「跟踪」部分：章节/轮次进度、错题回收、阶段里程碑、学习计时，顺带和墨墨联动。
 *
 * <p>和 {@link ExamService} 的分工：ExamService 管**计划/每日任务/打卡/统计/文案**，这里管**长线跟踪**。
 * 两者是单向依赖（ExamService → 这里），所以这里**不要**反过来调 ExamService（计时结束只返回分钟数，
 * 由调用方去记打卡）。
 */
@Service
public class ExamTrackService {

    private static final Logger log = LoggerFactory.getLogger(ExamTrackService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_MISTAKE_TITLE_CHARS = 300;
    private static final int MAX_MISTAKE_DETAIL_CHARS = 1000;
    private static final int MAX_MILESTONE_NAME_CHARS = 120;
    private static final int MAX_NOTE_CHARS = 300;
    private static final long MAX_SESSION_MINUTES = 12 * 60;
    private static final int MISTAKE_QUESTIONS = 3;

    private final ExamProgressRepository progressRepository;
    private final ExamMistakeRepository mistakeRepository;
    private final ExamMilestoneRepository milestoneRepository;
    private final ExamTaskRepository taskRepository;
    private final MaimemoService maimemoService;
    private final ZoneId zone;
    /** userId → 正在计时的那一段（进程内；重启就丢，属于可接受的取舍） */
    private final ConcurrentMap<String, StudySession> sessions = new ConcurrentHashMap<>();

    /** 计时中的一段学习：科目 + 开始时间 */
    public record StudySession(String subject, LocalDateTime startedAt) {
    }

    /** 结束计时后的结果：科目 + 时长（分钟） */
    public record StudyStop(String subject, long minutes) {
    }

    public ExamTrackService(ExamProgressRepository progressRepository,
                            ExamMistakeRepository mistakeRepository,
                            ExamMilestoneRepository milestoneRepository,
                            ExamTaskRepository taskRepository,
                            MaimemoService maimemoService,
                            @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.progressRepository = progressRepository;
        this.mistakeRepository = mistakeRepository;
        this.milestoneRepository = milestoneRepository;
        this.taskRepository = taskRepository;
        this.maimemoService = maimemoService;
        this.zone = parseZone(timeZone);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    // ==================== 章节/轮次进度 ====================

    @Transactional
    public String saveProgress(String userId, String subject, String group, String phase, String title,
                               Integer total, Integer done, String unit, String dueDate, String note) {
        if (title == null || title.isBlank()) {
            return "进度条要有个单元名（例如「高数第三章」或「408 王道数据结构」）。";
        }
        LocalDate due = ExamService.parseDate(dueDate, today());
        if (dueDate != null && !dueDate.isBlank() && due == null) {
            return "截止日期没看懂（要 2027-03-31 这种写法）。";
        }
        String subjectName = subject == null || subject.isBlank() ? "未分类" : subject.trim();
        LocalDateTime now = LocalDateTime.now(zone);
        ExamProgress item = new ExamProgress();
        item.setUserId(userId);
        item.setSubject(ExamService.clip(subjectName, 60));
        item.setSubjectGroup(ExamService.clip(group == null || group.isBlank()
                ? ExamService.inferGroup(subjectName) : group, 60));
        item.setPhase(normalizePhase(phase));
        item.setTitle(ExamService.clip(title, MAX_TITLE_CHARS));
        item.setTotal(total == null || total <= 0 ? null : total);
        item.setDone(done == null || done < 0 ? 0 : done);
        item.setUnit(ExamService.clip(unit == null || unit.isBlank() ? "章" : unit, 16));
        item.setDueDate(due);
        item.setNote(ExamService.clip(note, MAX_NOTE_CHARS));
        item.setLastTouchedAt(now);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        progressRepository.save(item);
        log.info("考研进度已记录 user={} group={} title={} {}/{}", userId, item.getSubjectGroup(),
                item.getTitle(), item.getDone(), item.getTotal());
        return "记下了：" + progressLine(item);
    }

    /** 行内/工具用的推进：{@code delta} 增量优先，{@code absoluteDone} 直接设成某个值（「这一章做完了」）。 */
    @Transactional
    public String bumpProgress(String userId, Long id, Integer delta, Integer absoluteDone) {
        ExamProgress item = id == null ? null : progressRepository.findById(id).orElse(null);
        if (item == null || !userId.equals(item.getUserId())) {
            return "没找到这条进度（id 可能不对，可以让我列一下当前进度）。";
        }
        int next = absoluteDone != null ? absoluteDone
                : (item.getDone() == null ? 0 : item.getDone()) + (delta == null ? 1 : delta);
        if (next < 0) {
            next = 0;
        }
        if (item.getTotal() != null && next > item.getTotal()) {
            next = item.getTotal();
        }
        item.setDone(next);
        item.setLastTouchedAt(LocalDateTime.now(zone));
        item.setUpdatedAt(LocalDateTime.now(zone));
        progressRepository.save(item);
        return progressLine(item);
    }

    @Transactional
    public String deleteProgress(String userId, Long id) {
        ExamProgress item = id == null ? null : progressRepository.findById(id).orElse(null);
        if (item == null || !userId.equals(item.getUserId())) {
            return "没找到这条进度。";
        }
        progressRepository.delete(item);
        return "已删掉进度「" + item.getTitle() + "」。";
    }

    public List<ExamProgress> progress(String userId) {
        return progressRepository.findByUserIdOrderBySubjectGroupAscIdAsc(userId);
    }

    public List<ExamProgress> overdueProgress(String userId) {
        LocalDate date = today();
        return progressRepository.findByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(userId, date).stream()
                .filter(item -> item.getTotal() == null || item.getDone() == null || item.getDone() < item.getTotal())
                .toList();
    }

    public String progressText(String userId) {
        List<ExamProgress> items = progress(userId);
        if (items.isEmpty()) {
            return "还没有记章节进度。可以这样说：「408 数据结构王道 8 章，我做完 3 章了」「数学二高数第三章 120 题，做到 40 题」。";
        }
        Map<String, List<ExamProgress>> byGroup = new LinkedHashMap<>();
        items.forEach(item -> byGroup.computeIfAbsent(groupOf(item), key -> new ArrayList<>()).add(item));
        StringBuilder sb = new StringBuilder("📚 复习进度\n");
        for (Map.Entry<String, List<ExamProgress>> entry : byGroup.entrySet()) {
            sb.append("【").append(entry.getKey()).append("】\n");
            for (ExamProgress item : entry.getValue()) {
                sb.append("· ").append(progressLine(item));
                if (item.getNote() != null && !item.getNote().isBlank()) {
                    sb.append("｜").append(item.getNote());
                }
                sb.append("\n");
            }
        }
        List<ExamProgress> overdue = overdueProgress(userId);
        if (!overdue.isEmpty()) {
            sb.append("⚠️ 超期未完成：");
            sb.append(overdue.stream().limit(3)
                    .map(item -> item.getTitle() + "（计划 " + item.getDueDate().format(DATE) + "）")
                    .reduce((a, b) -> a + "、" + b).orElse(""));
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String progressLine(ExamProgress item) {
        StringBuilder sb = new StringBuilder();
        if (item.getSubject() != null && !item.getSubject().isBlank()) {
            sb.append(item.getSubject()).append(" · ");
        }
        sb.append(item.getTitle());
        if (item.getTotal() != null) {
            sb.append("：").append(item.getDone() == null ? 0 : item.getDone()).append("/").append(item.getTotal())
                    .append(item.getUnit() == null ? "" : item.getUnit())
                    .append("（").append(percent(item.getDone(), item.getTotal())).append("%）");
        } else if (item.getDone() != null && item.getDone() > 0) {
            sb.append("：已完成 ").append(item.getDone()).append(item.getUnit() == null ? "" : item.getUnit());
        }
        if (item.getPhase() != null) {
            sb.append(" [").append(phaseText(item.getPhase())).append("]");
        }
        if (item.getDueDate() != null) {
            sb.append("，计划 ").append(item.getDueDate().format(DATE));
        }
        return sb.toString();
    }

    /** 面板：各科（组）完成率，柱状图用 */
    public List<Map<String, Object>> groupPercents(String userId) {
        Map<String, int[]> byGroup = new LinkedHashMap<>();
        for (ExamProgress item : progress(userId)) {
            int[] counters = byGroup.computeIfAbsent(groupOf(item), key -> new int[2]);
            counters[0] += item.getDone() == null ? 0 : item.getDone();
            counters[1] += item.getTotal() == null ? 0 : item.getTotal();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        byGroup.forEach((group, counters) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", group);
            row.put("value", counters[1] == 0 ? 0 : (int) Math.round(counters[0] * 100.0 / counters[1]));
            row.put("done", counters[0]);
            row.put("total", counters[1]);
            items.add(row);
        });
        return items;
    }

    public Map<String, Object> progressPanel(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate date = today();
        for (ExamProgress item : progress(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("group", groupOf(item));
            row.put("subject", item.getSubject() == null ? "" : item.getSubject());
            row.put("title", item.getTitle());
            row.put("phase", phaseText(item.getPhase()));
            row.put("total", item.getTotal() == null ? "" : item.getTotal());
            row.put("done", item.getDone() == null ? 0 : item.getDone());
            row.put("percent", percent(item.getDone(), item.getTotal()));
            row.put("progress", item.getTotal() == null
                    ? (item.getDone() == null ? "—" : "已做 " + item.getDone() + (item.getUnit() == null ? "" : item.getUnit()))
                    : (item.getDone() == null ? 0 : item.getDone()) + "/" + item.getTotal()
                            + (item.getUnit() == null ? "" : item.getUnit())
                            + "（" + percent(item.getDone(), item.getTotal()) + "%）");
            boolean finished = item.getTotal() != null && item.getDone() != null && item.getDone() >= item.getTotal();
            boolean overdue = !finished && item.getDueDate() != null && !item.getDueDate().isAfter(date);
            row.put("state", finished ? "DONE" : overdue ? "OVERDUE" : "ONGOING");
            row.put("dueDate", item.getDueDate() == null ? "" : item.getDueDate().format(FULL));
            row.put("note", item.getNote() == null ? "" : item.getNote());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    // ==================== 错题回收 ====================

    @Transactional
    public String addMistake(String userId, String subject, String title, String detail, String source) {
        if (title == null || title.isBlank()) {
            return "错题要有个摘要（题目或知识点，例如「快排最坏复杂度推导」）。";
        }
        String subjectName = subject == null || subject.isBlank() ? "未分类" : subject.trim();
        LocalDateTime now = LocalDateTime.now(zone);
        ExamMistake mistake = new ExamMistake();
        mistake.setUserId(userId);
        mistake.setSubject(ExamService.clip(subjectName, 60));
        mistake.setSubjectGroup(ExamService.clip(ExamService.inferGroup(subjectName), 60));
        mistake.setTitle(ExamService.clip(title, MAX_MISTAKE_TITLE_CHARS));
        mistake.setDetail(ExamService.clip(detail, MAX_MISTAKE_DETAIL_CHARS));
        mistake.setSource(ExamService.clip(source, 60));
        mistake.setReviewStage(0);
        mistake.setCorrectStreak(0);
        mistake.setStatus(ExamMistake.STATUS_OPEN);
        mistake.setNextReviewDate(today().plusDays(ExamMistake.REVIEW_INTERVALS[0]));
        mistake.setCreatedAt(now);
        mistake.setUpdatedAt(now);
        mistakeRepository.save(mistake);
        int due = dueMistakes(userId, today()).size();
        return "错题记下了：「" + mistake.getTitle() + "」（" + mistake.getSubjectGroup() + "）\n明天开始回收，"
                + "答对往后推（1/3/7/15/30 天），答错回到第一天。今天到期要复习的有 " + due + " 条。";
    }

    /** 复习结果：RIGHT 往后推一轮，WRONG 回到第一天；走完整轮 → 掌握。 */
    @Transactional
    public String reviewMistake(String userId, Long id, String result) {
        ExamMistake mistake = id == null ? null : mistakeRepository.findById(id).orElse(null);
        if (mistake == null || !userId.equals(mistake.getUserId())) {
            return "没找到这条错题（id 可能不对，可以让我列一下错题本）。";
        }
        boolean right = isRight(result);
        LocalDateTime now = LocalDateTime.now(zone);
        mistake.setLastReviewedAt(now);
        mistake.setUpdatedAt(now);
        if (!right) {
            mistake.setReviewStage(0);
            mistake.setCorrectStreak(0);
            mistake.setNextReviewDate(today().plusDays(ExamMistake.REVIEW_INTERVALS[0]));
            mistake.setStatus(ExamMistake.STATUS_OPEN);
            mistakeRepository.save(mistake);
            return "又错一次，回到第一天：「" + mistake.getTitle() + "」，明天再抽它。别急着抄答案，先把卡住的那一步找出来。";
        }
        int stage = (mistake.getReviewStage() == null ? 0 : mistake.getReviewStage()) + 1;
        mistake.setCorrectStreak((mistake.getCorrectStreak() == null ? 0 : mistake.getCorrectStreak()) + 1);
        if (stage >= ExamMistake.REVIEW_INTERVALS.length) {
            mistake.setReviewStage(stage);
            mistake.setStatus(ExamMistake.STATUS_MASTERED);
            mistake.setNextReviewDate(null);
            mistakeRepository.save(mistake);
            return "✅「" + mistake.getTitle() + "」连过 " + ExamMistake.REVIEW_INTERVALS.length
                    + " 轮，从错题本毕业了。";
        }
        mistake.setReviewStage(stage);
        long interval = ExamMistake.REVIEW_INTERVALS[stage];
        mistake.setNextReviewDate(today().plusDays(interval));
        mistake.setStatus(ExamMistake.STATUS_OPEN);
        mistakeRepository.save(mistake);
        return "✅「" + mistake.getTitle() + "」第 " + stage + " 轮过了，" + interval + " 天后再抽一次。";
    }

    @Transactional
    public String dropMistake(String userId, Long id) {
        ExamMistake mistake = id == null ? null : mistakeRepository.findById(id).orElse(null);
        if (mistake == null || !userId.equals(mistake.getUserId())) {
            return "没找到这条错题。";
        }
        mistake.setStatus(ExamMistake.STATUS_DROPPED);
        mistake.setNextReviewDate(null);
        mistake.setUpdatedAt(LocalDateTime.now(zone));
        mistakeRepository.save(mistake);
        return "「" + mistake.getTitle() + "」已从错题本移出（不再提醒）。";
    }

    public List<ExamMistake> dueMistakes(String userId) {
        return dueMistakes(userId, today());
    }

    private List<ExamMistake> dueMistakes(String userId, LocalDate date) {
        return mistakeRepository.findByUserIdAndStatusAndNextReviewDateLessThanEqualOrderByNextReviewDateAsc(
                userId, ExamMistake.STATUS_OPEN, date);
    }

    public List<ExamMistake> openMistakes(String userId) {
        return mistakeRepository.findByUserIdAndStatusOrderByIdAsc(userId, ExamMistake.STATUS_OPEN);
    }

    public String mistakesText(String userId) {
        List<ExamMistake> due = dueMistakes(userId);
        List<ExamMistake> all = openMistakes(userId);
        long mastered = mistakeRepository.countByUserIdAndStatus(userId, ExamMistake.STATUS_MASTERED);
        if (all.isEmpty() && mastered == 0) {
            return "错题本还是空的。做错题的时候说一句「记错题：快排最坏复杂度推导错了」我就收着，之后按 1/3/7/15/30 天抽你。";
        }
        StringBuilder sb = new StringBuilder("📕 错题本\n");
        sb.append("待回收 ").append(all.size()).append(" 条，今天到期 ").append(due.size())
                .append(" 条，已掌握 ").append(mastered).append(" 条\n");
        if (!due.isEmpty()) {
            sb.append("今天要复习：\n");
            due.stream().limit(5).forEach(item -> sb.append("· #").append(item.getId()).append(" ")
                    .append(item.getSubjectGroup()).append("｜").append(item.getTitle())
                    .append("（第 ").append(item.getReviewStage() == null ? 0 : item.getReviewStage())
                    .append(" 轮）\n"));
        }
        List<ExamMistake> upcoming = all.stream().filter(item -> !due.contains(item)).limit(5).toList();
        if (!upcoming.isEmpty()) {
            sb.append("之后要复习：\n");
            upcoming.forEach(item -> sb.append("· #").append(item.getId()).append(" ").append(item.getTitle())
                    .append("（").append(item.getNextReviewDate() == null ? "—" : item.getNextReviewDate().format(DATE))
                    .append("）\n"));
        }
        sb.append("复习完说「错题 #id 记得 / 又错了」，或者说「错题都过了」。");
        return sb.toString().stripTrailing();
    }

    public Map<String, Object> mistakePanel(String userId) {
        LocalDate date = today();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExamMistake mistake : openMistakes(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", mistake.getId());
            row.put("group", mistake.getSubjectGroup() == null ? "" : mistake.getSubjectGroup());
            row.put("title", mistake.getTitle());
            row.put("stage", (mistake.getReviewStage() == null ? 0 : mistake.getReviewStage())
                    + "/" + ExamMistake.REVIEW_INTERVALS.length);
            row.put("nextReviewDate", mistake.getNextReviewDate() == null ? "" : mistake.getNextReviewDate().format(FULL));
            boolean due = mistake.getNextReviewDate() != null && !mistake.getNextReviewDate().isAfter(date);
            row.put("state", due ? "DUE" : "WAITING");
            row.put("source", mistake.getSource() == null ? "" : mistake.getSource());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    // ==================== 阶段里程碑 ====================

    @Transactional
    public String saveMilestone(String userId, String name, String dueDate, String note) {
        if (name == null || name.isBlank()) {
            return "里程碑要有个名字（例如「408 一轮」「数学二强化」）。";
        }
        LocalDate due = ExamService.parseDate(dueDate, today());
        if (dueDate != null && !dueDate.isBlank() && due == null) {
            return "截止日期没看懂（要 2027-03-31 这种写法）。";
        }
        LocalDateTime now = LocalDateTime.now(zone);
        ExamMilestone milestone = new ExamMilestone();
        milestone.setUserId(userId);
        milestone.setName(ExamService.clip(name, MAX_MILESTONE_NAME_CHARS));
        milestone.setDueDate(due);
        milestone.setNote(ExamService.clip(note, MAX_NOTE_CHARS));
        milestone.setCreatedAt(now);
        milestone.setUpdatedAt(now);
        milestoneRepository.save(milestone);
        return "里程碑记下了：" + milestoneLine(milestone) + "。到点没完成我会在周复盘里点名。";
    }

    @Transactional
    public String completeMilestone(String userId, Long id, boolean done) {
        ExamMilestone milestone = id == null ? null : milestoneRepository.findById(id).orElse(null);
        if (milestone == null || !userId.equals(milestone.getUserId())) {
            return "没找到这个里程碑。";
        }
        milestone.setDoneAt(done ? LocalDateTime.now(zone) : null);
        milestone.setUpdatedAt(LocalDateTime.now(zone));
        milestoneRepository.save(milestone);
        return done ? "✅ 里程碑完成：" + milestone.getName() : "已把「" + milestone.getName() + "」改回未完成。";
    }

    @Transactional
    public String deleteMilestone(String userId, Long id) {
        ExamMilestone milestone = id == null ? null : milestoneRepository.findById(id).orElse(null);
        if (milestone == null || !userId.equals(milestone.getUserId())) {
            return "没找到这个里程碑。";
        }
        milestoneRepository.delete(milestone);
        return "已删掉里程碑「" + milestone.getName() + "」。";
    }

    public List<ExamMilestone> milestones(String userId) {
        return milestoneRepository.findByUserIdOrderByDueDateAscIdAsc(userId);
    }

    /** 超期未完成的里程碑（周复盘与早推送点名用） */
    public List<ExamMilestone> overdueMilestones(String userId) {
        LocalDate date = today();
        return milestones(userId).stream()
                .filter(item -> item.getDoneAt() == null && item.getDueDate() != null && item.getDueDate().isBefore(date))
                .toList();
    }

    public String milestonesText(String userId) {
        List<ExamMilestone> items = milestones(userId);
        if (items.isEmpty()) {
            return "还没有里程碑。建议按阶段定三条，例如「基础一轮 2027-03-31」「408 一轮 2027-06-30」「真题一遍 2027-11-30」。";
        }
        StringBuilder sb = new StringBuilder("🎯 阶段里程碑\n");
        LocalDate date = today();
        for (ExamMilestone item : items) {
            sb.append("· ").append(milestoneLine(item));
            if (item.getDoneAt() == null && item.getDueDate() != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(date, item.getDueDate());
                sb.append(days >= 0 ? "，还有 " + days + " 天" : "，⚠️ 已超期 " + (-days) + " 天");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String milestoneLine(ExamMilestone item) {
        StringBuilder sb = new StringBuilder(item.getName());
        if (item.getDoneAt() != null) {
            sb.append(" ✅（").append(item.getDoneAt().toLocalDate().format(DATE)).append(" 完成）");
        } else if (item.getDueDate() != null) {
            sb.append("（计划 ").append(item.getDueDate().format(FULL)).append("）");
        }
        return sb.toString();
    }

    public Map<String, Object> milestonePanel(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate date = today();
        for (ExamMilestone item : milestones(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("name", item.getName());
            row.put("dueDate", item.getDueDate() == null ? "" : item.getDueDate().format(FULL));
            row.put("doneAt", item.getDoneAt() == null ? "" : item.getDoneAt().toLocalDate().format(FULL));
            boolean done = item.getDoneAt() != null;
            long days = item.getDueDate() == null ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(date, item.getDueDate());
            row.put("state", done ? "DONE" : days < 0 ? "OVERDUE" : "ONGOING");
            row.put("remainDays", done ? "" : item.getDueDate() == null ? "" : String.valueOf(days));
            row.put("note", item.getNote() == null ? "" : item.getNote());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    // ==================== 学习计时 ====================

    /** 开始计时；已经在计时就先返回上一段的状态（避免出现两段并行） */
    public String startStudy(String userId, String subject) {
        StudySession existing = sessions.get(userId);
        if (existing != null) {
            long minutes = Duration.between(existing.startedAt(), LocalDateTime.now(zone)).toMinutes();
            return "上一段「" + existing.subject() + "」还在计时（已经 " + minutes + " 分钟）。"
                    + "说「结束学习」我就先把它记下来。";
        }
        String name = subject == null || subject.isBlank() ? "学习" : subject.trim();
        sessions.put(userId, new StudySession(ExamService.clip(name, 60), LocalDateTime.now(zone)));
        return "⏱ 开始计时：" + name + "。学完说「结束学习」，我把这段时长记进当天的打卡。";
    }

    /** 结束计时并返回这一段（没在计时返回 null） */
    public StudyStop stopStudy(String userId) {
        StudySession session = sessions.remove(userId);
        if (session == null) {
            return null;
        }
        long minutes = Duration.between(session.startedAt(), LocalDateTime.now(zone)).toMinutes();
        minutes = Math.max(1, Math.min(minutes, MAX_SESSION_MINUTES));
        return new StudyStop(session.subject(), minutes);
    }

    /** 计时中的人（面板与「结束学习」判断用） */
    public StudySession session(String userId) {
        return sessions.get(userId);
    }

    public String sessionText(String userId) {
        StudySession session = sessions.get(userId);
        if (session == null) {
            return null;
        }
        long minutes = Duration.between(session.startedAt(), LocalDateTime.now(zone)).toMinutes();
        return "计时中：" + session.subject() + "（已 " + minutes + " 分钟）";
    }

    public long sessionMinutes(String userId) {
        StudySession session = sessions.get(userId);
        return session == null ? 0 : Duration.between(session.startedAt(), LocalDateTime.now(zone)).toMinutes();
    }

    // ==================== 墨墨联动 ====================

    /**
     * 把墨墨今日进度接到考研里：进度行照实显示；如果今天的任务里有「背单词」且墨墨已清空，
     * 就顺手把它勾掉（只动自己那一行，不碰别的任务）。
     *
     * @return 一行可拼进推送的文案；拿不到墨墨数据时返回 null（推送里就不显示这行）
     */
    @Transactional
    public String syncMaimemo(String userId) {
        if (maimemoService == null) {
            return null;
        }
        try {
            Map<String, Object> snapshot = maimemoService.refresh();
            String status = String.valueOf(snapshot.get("status"));
            if (!MaimemoService.STATUS_OK.equals(status)) {
                return null;
            }
            Object raw = snapshot.get("progress");
            if (!(raw instanceof Map<?, ?> progress)) {
                return null;
            }
            int finished = number(progress.get("finished"));
            int total = number(progress.get("total"));
            if (total <= 0) {
                return null;
            }
            String line = "📖 墨墨背单词：" + finished + "/" + total + (finished >= total ? " ✅" : "");
            if (finished < total) {
                return line;
            }
            ExamTask target = findTodayWordTask(userId);
            if (target == null || ExamTask.STATUS_DONE.equals(target.getStatus())) {
                return line;
            }
            target.setStatus(ExamTask.STATUS_DONE);
            target.setDoneAt(LocalDateTime.now(zone));
            target.setNote(ExamService.clip("墨墨已完成 " + finished + "/" + total, MAX_NOTE_CHARS));
            target.setUpdatedAt(LocalDateTime.now(zone));
            taskRepository.save(target);
            log.info("墨墨已完成，顺手勾掉背单词任务 user={} task={}", userId, target.getId());
            return line + "（今天的背单词任务已顺手勾掉）";
        } catch (RuntimeException exception) {
            log.warn("读取墨墨进度失败，考研推送跳过这一行 user={}：{}", userId, exception.toString());
            return null;
        }
    }

    private ExamTask findTodayWordTask(String userId) {
        return taskRepository.findByUserIdAndPlanDateOrderBySortOrderAscIdAsc(userId, today()).stream()
                .filter(task -> containsWord(task.getSubject()) || containsWord(task.getContent()))
                .findFirst()
                .orElse(null);
    }

    private boolean containsWord(String text) {
        return text != null && (text.contains("单词") || text.contains("背词") || text.contains("词汇"));
    }

    /** 今日到期的错题摘要（早推送/进度文案里抽题用） */
    public List<String> dueMistakeLines(String userId) {
        return dueMistakes(userId).stream()
                .sorted(Comparator.comparing(ExamMistake::getNextReviewDate))
                .limit(MISTAKE_QUESTIONS)
                .map(item -> "#" + item.getId() + " " + (item.getSubjectGroup() == null ? "" : item.getSubjectGroup() + "｜")
                        + item.getTitle())
                .toList();
    }

    public int dueMistakeCount(String userId) {
        return dueMistakes(userId).size();
    }

    // ==================== 小工具 ====================

    private String groupOf(ExamProgress item) {
        return item.getSubjectGroup() == null || item.getSubjectGroup().isBlank()
                ? ExamService.inferGroup(item.getSubject()) : item.getSubjectGroup();
    }

    private int percent(Integer done, Integer total) {
        if (total == null || total <= 0) {
            return 0;
        }
        return (int) Math.round((done == null ? 0 : done) * 100.0 / total);
    }

    private boolean isRight(String result) {
        if (result == null) {
            return false;
        }
        String value = result.trim().toUpperCase(java.util.Locale.ROOT);
        return value.contains("RIGHT") || value.contains("OK") || value.contains("记得")
                || value.contains("对") || value.contains("过") || value.contains("YES")
                || value.contains("PASS") || value.contains("TRUE") || value.contains("1");
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return null;
        }
        String value = phase.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.contains("基础") || value.contains("BASIC")) {
            return ExamProgress.PHASE_BASIC;
        }
        if (value.contains("强化") || value.contains("INTENSIVE")) {
            return ExamProgress.PHASE_INTENSIVE;
        }
        if (value.contains("冲刺") || value.contains("SPRINT")) {
            return ExamProgress.PHASE_SPRINT;
        }
        if (value.contains("真题") || value.contains("PAST")) {
            return ExamProgress.PHASE_PAST_PAPER;
        }
        return null;
    }

    private String phaseText(String phase) {
        if (ExamProgress.PHASE_INTENSIVE.equals(phase)) {
            return "强化";
        }
        if (ExamProgress.PHASE_SPRINT.equals(phase)) {
            return "冲刺";
        }
        if (ExamProgress.PHASE_PAST_PAPER.equals(phase)) {
            return "真题";
        }
        return "基础";
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static ZoneId parseZone(String value) {
        try {
            return value == null || value.isBlank() ? DEFAULT_ZONE : ZoneId.of(value.trim());
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    /** 面板 form 区块的初值（可编辑计划用） */
    public Map<String, Object> studySessionPanel(String userId) {
        Map<String, Object> rows = new LinkedHashMap<>();
        StudySession session = session(userId);
        rows.put("label", "计时");
        rows.put("value", session == null ? "没有在计时"
                : "计时中：" + session.subject() + "（已 " + sessionMinutes(userId) + " 分钟）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", List.of(rows));
        return result;
    }

    public String lastTouchedText(ExamProgress item) {
        return item.getLastTouchedAt() == null ? "" : item.getLastTouchedAt().format(STAMP);
    }
}
