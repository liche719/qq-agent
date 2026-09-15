package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.TurnTraceStore;
import com.liche.wechatagent.self.AgentCommitment;
import com.liche.wechatagent.self.AgentLesson;
import com.liche.wechatagent.self.AgentQuest;
import com.liche.wechatagent.self.AgentQuestNote;
import com.liche.wechatagent.self.AgentQuestRun;
import com.liche.wechatagent.self.AgentReflection;
import com.liche.wechatagent.self.AgentSelfBlock;
import com.liche.wechatagent.self.AgentSelfEvent;
import com.liche.wechatagent.self.AgentSelfUtterance;
import com.liche.wechatagent.self.AgentStance;
import com.liche.wechatagent.self.SelfQuestService;
import com.liche.wechatagent.self.SelfReflectionService;
import com.liche.wechatagent.self.SelfService;
import com.liche.wechatagent.self.SelfSpeakService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 自主模块的**只读**面板接口（编辑一律走对话，见 docs/self-layer-spec.md §10）。
 *
 * <p>用途：让"它自己那一侧"可见——块 / 时间线 / 承诺账 / **倾向（带证据区间）** /
 * **反思与成本** / **分歧** / **每日变更量**。核心目的不是"看它的性格"，
 * 而是让**它这轮看到了什么、花了多少、依据哪几条**都能点开查（spec §12）。
 */
@RestController
@RequestMapping("/api/admin/self")
public class AdminSelfController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SelfService selfService;
    private final SelfReflectionService reflectionService;
    private final SelfQuestService questService;
    private final SelfSpeakService speakService;
    private final TurnTraceStore turnTraceStore;

    public AdminSelfController(SelfService selfService, SelfReflectionService reflectionService,
                               SelfQuestService questService, SelfSpeakService speakService,
                               TurnTraceStore turnTraceStore) {
        this.selfService = selfService;
        this.reflectionService = reflectionService;
        this.questService = questService;
        this.speakService = speakService;
        this.turnTraceStore = turnTraceStore;
    }

    /**
     * 「这一轮它看到了什么」（上下文检查器）：本轮实际注入的上下文按段拆开，每段字数 / 上限 / 占比 + 原文预览。
     * 这是"它这轮为什么这么说"的第一现场——答案永远在"它这轮看到了什么"里（spec §12）。
     */
    @GetMapping("/turn")
    public Map<String, Object> turn() {
        Optional<TurnTraceStore.Turn> found = traceTurn();
        if (found.isEmpty()) {
            return Map.of("rows", List.of());
        }
        TurnTraceStore.Turn turn = found.get();
        int total = Math.max(1, turn.promptChars());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TurnTraceStore.Section section : turn.sections()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("section", section.label());
            row.put("usage", section.chars() + (section.limit() > 0 ? "/" + section.limit() : "（没设上限）"));
            row.put("ratio", Math.round(section.chars() * 1000.0 / total) / 10.0 + "%");
            row.put("preview", section.preview());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 「这一轮的调用链」：LLM 与工具调用的逐步列表——失败常藏在中间步骤（spec §12） */
    @GetMapping("/trace")
    public Map<String, Object> trace() {
        Optional<TurnTraceStore.Turn> found = traceTurn();
        if (found.isEmpty()) {
            return Map.of("rows", List.of());
        }
        TurnTraceStore.Turn turn = found.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 1;
        for (TurnTraceStore.Step step : turn.steps()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", String.valueOf(index++));
            row.put("kind", step.kind());
            row.put("name", step.name());
            row.put("result", (step.ok() ? "成功" : "失败") + "｜" + step.durationMs() + " ms");
            row.put("detail", step.detail() == null ? "—" : step.detail());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 机主只有一个：优先用配了归属人的那个用户查；没有再退回最近一轮 */
    private Optional<TurnTraceStore.Turn> traceTurn() {
        String owner = selfService.owner();
        Optional<TurnTraceStore.Turn> byOwner = turnTraceStore.lastTurn(owner);
        return byOwner.isPresent() ? byOwner : turnTraceStore.latest();
    }

    /**
     * 排障入口：手动跑一次反思（spec §4 的「手动」档）。预算与重要度两道闸照常生效——
     * 这个按钮是给"它到底会不会整合"用的，不是绕过规则的旁路。
     */
    @PostMapping("/reflect")
    public Map<String, Object> reflectNow() {
        String owner = selfService.owner();
        if (owner == null) {
            return Map.of("message", "自主模块未工作（" + selfService.inactiveReason() + "）");
        }
        SelfReflectionService.Outcome outcome = reflectionService.reflect(owner, "manual");
        if (outcome.ran()) {
            return Map.of("message", "反思完成 #" + outcome.reflectionId() + "：" + outcome.conclusion()
                    + "｜倾向[" + outcome.stances() + "]｜承诺判欠 " + outcome.commitmentsBroken()
                    + " 条｜tokens " + outcome.promptTokens() + "/" + outcome.completionTokens());
        }
        String extra = outcome.stances().touched() ? "；倾向维护跑了[" + outcome.stances() + "]" : "";
        return Map.of("message", "这次没反思：" + outcome.reason() + extra);
    }

    /** 状态条：模块开没开、有多少东西、今天花了多少、有没有该复查的倾向。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("模块状态", selfService.isActive() ? "工作中" : "未工作（" + selfService.inactiveReason() + "）"));
        List<AgentSelfBlock> blocks = selfService.blocks();
        rows.add(row("自己的块", blocks.isEmpty() ? "（空）" : blocks.size() + " 个"));
        long activeStances = selfService.countActiveStances();
        int dueStances = selfService.dueStances(now).size();
        rows.add(row("活跃倾向", activeStances + " 条"
                + (dueStances == 0 ? "" : "（" + dueStances + " 条该复查了）")));
        rows.add(row("未结承诺", String.valueOf(selfService.openCommitments().size())));
        List<AgentReflection> today = selfService.recentReflections(50).stream()
                .filter(reflection -> reflection.getCreatedAt() != null
                        && !reflection.getCreatedAt().isBefore(LocalDate.now().atStartOfDay()))
                .toList();
        rows.add(row("今天反思", today.isEmpty() ? "还没跑" : today.size() + " 次 / "
                + today.stream().mapToInt(this::tokensOf).sum() + " tokens"));
        rows.add(row("分歧（近 7 天）", disagreements(now).size() + " 次"));
        List<AgentLesson> activeLessons = selfService.activeLessons();
        long dueLessons = activeLessons.stream().filter(lesson -> lesson.isDue(now)).count();
        rows.add(row("教训清单", activeLessons.isEmpty() ? "（空）" : activeLessons.size() + " 条"
                + (dueLessons == 0 ? "" : "（" + dueLessons + " 条该复查了）")));
        selfService.lastReflection().ifPresent(reflection ->
                rows.add(row("上次反思", (reflection.getCreatedAt() == null ? "—"
                        : humanize(Duration.between(reflection.getCreatedAt(), now)) + "：")
                        + clip(reflection.getConclusion(), 80))));
        rows.add(row("最近事件", selfService.recentEvents(20).size() + " 条（面板最多显示 200）"));
        Duration since = selfService.sinceLastEvent();
        rows.add(row("距上次动自己这边", since == null ? "还没有记录" : humanize(since)));
        return Map.of("rows", rows);
    }

    /** 它自己的块：类型 / 用量 / 版本 / 最后修改。 */
    @GetMapping("/blocks")
    public Map<String, Object> blocks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSelfBlock block : selfService.blocks()) {
            int used = block.getValue() == null ? 0 : block.getValue().length();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", block.getBlockType());
            row.put("usage", used + "/" + block.getCharLimit());
            row.put("version", "v" + block.getVersion());
            row.put("updatedAt", block.getUpdatedAt() == null ? "—" : block.getUpdatedAt().format(STAMP));
            row.put("value", block.getValue() == null ? "（空）" : block.getValue());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 它自己那侧的时间线（只追加，所以就是它的历史）。 */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSelfEvent event : selfService.recentEvents(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + event.getId());
            row.put("kind", event.getKind());
            row.put("content", event.getContent());
            row.put("evidence", event.getEvidence() == null ? "—" : event.getEvidence());
            row.put("createdAt", event.getCreatedAt() == null ? "—" : event.getCreatedAt().format(STAMP));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 账：许过的诺与预测。 */
    @GetMapping("/commitments")
    public Map<String, Object> commitments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCommitment commitment : selfService.openCommitments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + commitment.getId());
            row.put("content", commitment.getContent());
            row.put("due", commitment.getDueAt() == null ? "—" : commitment.getDueAt().format(DAY));
            row.put("status", commitment.getStatus());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 倾向（S）：**每条都能点开看证据区间**——这是"它凭什么这么说"的入口。 */
    @GetMapping("/stances")
    public Map<String, Object> stances() {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> due = selfService.dueStances(now).stream().map(AgentStance::getId).collect(Collectors.toSet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentStance stance : selfService.activeStances()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", stance.getContent());
            row.put("topic", stance.getTopic() + " / " + stance.getDirection());
            row.put("evidence", "支撑 " + stance.getSupportCount() + "（event "
                    + dash(stance.getEvidenceIds()) + "）｜反例 " + stance.getCounterCount()
                    + (stance.getCounterCount() != null && stance.getCounterCount() > 0
                    ? "（event " + dash(stance.getCounterIds()) + "）" : ""));
            row.put("revised", "改过 " + stance.getReviseCount() + " 次");
            row.put("review", (due.contains(stance.getId()) ? "「该复查了」｜" : "")
                    + "S=" + round(stance.getStability()) + " D=" + round(stance.getDifficulty())
                    + "｜上次 " + stamp(stance.getLastReviewAt()) + "｜下次 " + stamp(stance.getNextReviewAt()));
            row.put("formedAt", stamp(stance.getFormedAt()));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 反思与成本：每次读了哪几条事件、得出什么结论、花了多少（防止"20 次调用、产出为零"重演）。 */
    @GetMapping("/reflections")
    public Map<String, Object> reflections(@RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentReflection reflection : selfService.recentReflections(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + reflection.getId());
            row.put("trigger", reflection.getTriggerType() == null ? "—" : reflection.getTriggerType());
            row.put("conclusion", reflection.getConclusion());
            row.put("evidence", "读了 " + countIds(reflection.getInputEventIds()) + " 条（"
                    + dash(reflection.getInputEventIds()) + "）");
            row.put("cost", reflection.getCalls() + " 次调用｜" + tokensOf(reflection) + " tokens｜"
                    + reflection.getDurationMs() + " ms");
            row.put("createdAt", reflection.getCreatedAt() == null ? "—" : reflection.getCreatedAt().format(STAMP));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /**
     * 教训清单（三期领域①）：**复现次数就是标尺**——清单越来越长、复现率却没变化，
     * 说明它在写作文而不是在学（§9.2 的反装判据），所以这块必须把次数摆在最显眼处。
     */
    @GetMapping("/lessons")
    public Map<String, Object> lessons() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentLesson lesson : selfService.activeLessons()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + lesson.getId());
            row.put("category", lesson.getCategory() + "｜" + lesson.getTriggerType());
            row.put("what", "我做了：" + clip(lesson.getWhatIDid(), 60) + "；预期：" + clip(lesson.getExpectedResult(), 40)
                    + "；实际：" + clip(lesson.getWhatHappened(), 60));
            row.put("correction", lesson.getCorrection());
            row.put("recurrence", "复现 " + lesson.getRecurrenceCount() + " 次｜干净复查 "
                    + lesson.getCleanReviews() + " 次");
            row.put("review", (lesson.isDue(now) ? "「该复查了」｜" : "") + "S=" + round(lesson.getStability())
                    + " D=" + round(lesson.getDifficulty()) + "｜下次 " + stamp(lesson.getNextReviewAt()));
            row.put("status", AgentLesson.STATUS_CLOSED.equals(lesson.getStatus()) ? "已关闭"
                    : (AgentLesson.STATUS_IMPROVING.equals(lesson.getStatus()) ? "在好转" : "未解决"));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 每周教训事件数（§9.2：看**趋势线**，不看绝对值；明细分散在各条教训里） */
    @GetMapping("/lesson-bars")
    public Map<String, Object> lessonBars(@RequestParam(defaultValue = "8") int weeks) {
        int window = Math.min(26, Math.max(1, weeks));
        LocalDate thisWeek = LocalDate.now().minusDays(LocalDate.now().getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Integer> perWeek = new TreeMap<>();
        for (int index = window - 1; index >= 0; index--) {
            perWeek.put(thisWeek.minusWeeks(index), 0);
        }
        LocalDateTime since = thisWeek.minusWeeks(window - 1L).atStartOfDay();
        for (AgentSelfEvent event : selfService.eventsSince(since)) {
            if (!AgentSelfEvent.KIND_LESSON.equals(event.getKind()) || event.getCreatedAt() == null) {
                continue;
            }
            LocalDate eventWeek = event.getCreatedAt().toLocalDate();
            eventWeek = eventWeek.minusDays(eventWeek.getDayOfWeek().getValue() - 1L);
            if (perWeek.containsKey(eventWeek)) {
                perWeek.merge(eventWeek, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> items = perWeek.entrySet().stream()
                .map(entry -> item(entry.getKey().format(DAY).substring(5), entry.getValue()))
                .toList();
        return Map.of("items", items);
    }

    /** 分歧：它跟你意见不同的记录（§6 观察指标的**唯一来源**）。 */
    @GetMapping("/disagreements")
    public Map<String, Object> disagreements() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AgentSelfEvent> recent = selfService.recentEvents(200).stream()
                .filter(event -> AgentSelfEvent.KIND_DISAGREE.equals(event.getKind()))
                .limit(20)
                .toList();
        for (AgentSelfEvent event : recent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + event.getId());
            row.put("topic", (event.getTopic() == null ? "—" : event.getTopic())
                    + (event.getStance() == null ? "" : " / " + event.getStance()));
            row.put("content", event.getContent());
            row.put("outcome", persuaded(event) ? "后来改口了（近似）" : "还这么看");
            row.put("createdAt", event.getCreatedAt() == null ? "—" : event.getCreatedAt().format(STAMP));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 每日变更量（近 N 天）：它自己那侧每天被改了多少处——"看出它在长"的粗曲线。 */
    @GetMapping("/changelog-bars")
    public Map<String, Object> changelogBars(@RequestParam(defaultValue = "14") int days) {
        int window = Math.min(60, Math.max(1, days));
        LocalDateTime since = LocalDate.now().minusDays(window - 1L).atStartOfDay();
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        for (int index = 0; index < window; index++) {
            perDay.put(LocalDate.now().minusDays(index), 0);
        }
        for (AgentSelfEvent event : selfService.eventsSince(since)) {
            if (event.getCreatedAt() == null || AgentSelfEvent.KIND_REFLECT.equals(event.getKind())) {
                continue;
            }
            perDay.merge(event.getCreatedAt().toLocalDate(), 1, Integer::sum);
        }
        List<Map<String, Object>> items = perDay.entrySet().stream()
                .map(entry -> item(entry.getKey().format(DAY).substring(5), entry.getValue()))
                .toList();
        return Map.of("items", items);
    }

    /** 每天在"自己的事"上花的 token（近 N 天）。 */
    @GetMapping("/cost-bars")
    public Map<String, Object> costBars(@RequestParam(defaultValue = "14") int days) {
        int window = Math.min(60, Math.max(1, days));
        LocalDate from = LocalDate.now().minusDays(window - 1L);
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        for (int index = 0; index < window; index++) {
            perDay.put(LocalDate.now().minusDays(index), 0);
        }
        for (AgentReflection reflection : selfService.recentReflections(50)) {
            if (reflection.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = reflection.getCreatedAt().toLocalDate();
            if (day.isBefore(from)) {
                continue;
            }
            perDay.merge(day, tokensOf(reflection), Integer::sum);
        }
        // 领域②「它自己的时间」也是它自己的开销，必须算进来（不然这块成本看不见）
        for (AgentQuestRun run : selfService.recentQuestRuns(100)) {
            if (run.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = run.getCreatedAt().toLocalDate();
            if (day.isBefore(from)) {
                continue;
            }
            perDay.merge(day, (run.getPromptTokens() == null ? 0 : run.getPromptTokens())
                    + (run.getCompletionTokens() == null ? 0 : run.getCompletionTokens()), Integer::sum);
        }
        List<Map<String, Object>> items = perDay.entrySet().stream()
                .map(entry -> item(entry.getKey().format(DAY).substring(5), entry.getValue()))
                .toList();
        return Map.of("items", items);
    }

    // ---------------------------------------------------------------- 三期领域②：它自己的方向

    /**
     * 它自己的方向（三期领域②）：**这块地盘是它自己的**——题目、选择理由、下一步都由它自己出。
     * 面板要看的不是"有没有产出"，而是**它到底想做什么、为什么想做这个**（§7：偏好是选出来的）。
     */
    @GetMapping("/quests")
    public Map<String, Object> quests() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentQuest quest : selfService.quests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + quest.getId());
            row.put("title", quest.getTitle());
            row.put("why", quest.getWhy());
            row.put("next", quest.getNextStep() == null || quest.getNextStep().isBlank()
                    ? "—" : quest.getNextStep());
            row.put("progress", "推进 " + nz(quest.getStepCount()) + " 步｜笔记 " + nz(quest.getNoteCount())
                    + " 条｜自己撤回 " + nz(quest.getRetractCount()) + " 条");
            row.put("status", AgentQuest.STATUS_ACTIVE.equals(quest.getStatus()) ? "在做的"
                    : (AgentQuest.STATUS_PAUSED.equals(quest.getStatus()) ? "暂停" : "已收掉"));
            row.put("time", stamp(quest.getCreatedAt()));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 它为自己的方向写的笔记：撤回的也留着——撤回比例本身就是"它在核对"的证据（§9.3）。 */
    @GetMapping("/quest-notes")
    public Map<String, Object> questNotes(@RequestParam(defaultValue = "30") int limit) {
        int window = Math.min(200, Math.max(1, limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentQuest quest : selfService.quests()) {
            for (AgentQuestNote note : selfService.questNotes(quest.getId(), window)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "note:" + note.getId());
                row.put("quest", quest.getTitle());
                row.put("content", note.getContent());
                row.put("source", note.getSourceUrl() == null || note.getSourceUrl().isBlank()
                        ? "没写来源" : note.getSourceUrl());
                row.put("state", note.isRetracted() ? "已撤回：" + note.getRetractReason() : "有效");
                row.put("time", stamp(note.getCreatedAt()));
                rows.add(row);
            }
        }
        return Map.of("rows", rows);
    }

    /**
     * 每周写了多少条笔记（§9.3 的标尺之一）。
     *
     * <p>反装判据：**条数一直涨、撤回比例恒为 0 = 它在堆料**。所以撤回数不藏起来，
     * 它就在上面的清单里逐条可见。
     */
    @GetMapping("/quest-bars")
    public Map<String, Object> questBars(@RequestParam(defaultValue = "8") int weeks) {
        int window = Math.min(26, Math.max(1, weeks));
        LocalDate thisWeek = LocalDate.now().minusDays(LocalDate.now().getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Integer> perWeek = new TreeMap<>();
        for (int index = window - 1; index >= 0; index--) {
            perWeek.put(thisWeek.minusWeeks(index), 0);
        }
        LocalDateTime since = thisWeek.minusWeeks(window - 1L).atStartOfDay();
        for (AgentQuestNote note : selfService.recentQuestNotes(since)) {
            if (note.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = note.getCreatedAt().toLocalDate();
            LocalDate week = day.minusDays(day.getDayOfWeek().getValue() - 1L);
            if (perWeek.containsKey(week)) {
                perWeek.merge(week, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> items = perWeek.entrySet().stream()
                .map(entry -> item(entry.getKey().format(DAY).substring(5), entry.getValue()))
                .toList();
        return Map.of("items", items);
    }

    /** 每次"自己的时间"的作业：状态、它自己写的总结、以及花了多少（成本入账）。 */
    @GetMapping("/quest-runs")
    public Map<String, Object> questRuns(@RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentQuestRun run : selfService.recentQuestRuns(Math.min(100, Math.max(1, limit)))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + run.getId());
            row.put("status", switch (run.getStatus()) {
                case AgentQuestRun.STATUS_RAN -> "跑了";
                case AgentQuestRun.STATUS_RUNNING -> "跑到一半";
                case AgentQuestRun.STATUS_FAILED -> "失败";
                default -> "跳过";
            });
            row.put("summary", run.getSummary() == null ? (run.getReason() == null ? "—" : run.getReason())
                    : clip(run.getSummary(), 160));
            row.put("cost", (nz(run.getPromptTokens()) + nz(run.getCompletionTokens())) + " tokens｜"
                    + nz(run.getDurationMs()) + " ms");
            row.put("counts", "笔记 " + nz(run.getNoteCount()) + " 条｜撤回 " + nz(run.getRetractCount()) + " 条");
            row.put("time", stamp(run.getCreatedAt()));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /**
     * 它想说、但**没跟你说**的话（用户选的是先观察：口留着，不发）。
     *
     * <p>要能一眼看到两样：**它想说什么** 和 **它为什么想说**。后者才是有信息量的那一半——
     * 只看句子，看不出它到底在想什么。
     */
    @GetMapping("/utterances")
    public Map<String, Object> utterances(@RequestParam(defaultValue = "30") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSelfUtterance utterance : selfService.recentUtterances(Math.min(100, Math.max(1, limit)))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + utterance.getId());
            row.put("content", utterance.getContent());
            row.put("why", utterance.getWhy());
            row.put("state", switch (utterance.getStatus()) {
                case AgentSelfUtterance.STATUS_SENT -> "已说给你";
                case AgentSelfUtterance.STATUS_SUPPRESSED -> "被闸拦下";
                default -> "没说（口没开）";
            });
            row.put("time", stamp(utterance.getCreatedAt()));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 手动把攒着的话发一条出去（排障入口；每日条数与通道额度照常生效，不是绕过）。 */
    @PostMapping("/speak/flush")
    public Map<String, Object> speakNow() {
        SelfSpeakService.Outcome outcome = speakService.flush();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", outcome.sent());
        result.put("reason", outcome.reason() == null ? "" : outcome.reason());
        result.put("utteranceId", outcome.utteranceId() == null ? "" : ("#" + outcome.utteranceId()));
        result.put("text", outcome.text() == null ? "" : outcome.text());
        return result;
    }

    /** 手动叫它动一次（排障入口；预算与防抖仍然生效，不是绕过）。 */    @PostMapping("/quest/run")
    public Map<String, Object> runQuestNow() {
        SelfQuestService.Outcome outcome = questService.run("manual");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ran", outcome.ran());
        result.put("reason", outcome.reason() == null ? "" : outcome.reason());
        result.put("runId", outcome.runId() == null ? "" : ("run:" + outcome.runId()));
        result.put("summary", outcome.summary() == null ? "" : outcome.summary());
        result.put("tokens", outcome.promptTokens() + "/" + outcome.completionTokens());
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /** 近 7 天的分歧（overview 用它报次数） */
    private List<AgentSelfEvent> disagreements(LocalDateTime now) {
        LocalDateTime weekAgo = now.minusDays(7);
        return selfService.recentEvents(200).stream()
                .filter(event -> AgentSelfEvent.KIND_DISAGREE.equals(event.getKind()))
                .filter(event -> event.getCreatedAt() != null && event.getCreatedAt().isAfter(weekAgo))
                .toList();
    }

    /**
     * 有没有"改口"（**近似**）：分歧之后，同一类别出现了**方向不同**的判断。
     * 这只是旁证——真正推翻倾向要走证据（spec §5/§10），面板如实标"近似"。
     */
    private boolean persuaded(AgentSelfEvent disagree) {
        if (disagree.getTopic() == null || disagree.getCreatedAt() == null) {
            return false;
        }
        return selfService.judgesFor(disagree.getTopic()).stream()
                .filter(judge -> judge.getCreatedAt() != null && judge.getCreatedAt().isAfter(disagree.getCreatedAt()))
                .anyMatch(judge -> judge.getStance() != null && disagree.getStance() != null
                        && !judge.getStance().equalsIgnoreCase(disagree.getStance()));
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private int tokensOf(AgentReflection reflection) {
        int prompt = reflection.getPromptTokens() == null ? 0 : reflection.getPromptTokens();
        int completion = reflection.getCompletionTokens() == null ? 0 : reflection.getCompletionTokens();
        return prompt + completion;
    }

    private int countIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return 0;
        }
        return ids.split(",").length;
    }

    private String dash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    private String stamp(LocalDateTime time) {
        return time == null ? "—" : time.format(STAMP);
    }

    private double round(Double value) {
        if (value == null) {
            return 0;
        }
        return Math.round(value * 10) / 10.0;
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "—";
        }
        String trimmed = text.trim().replace('\n', ' ');
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, Math.max(0, max - 1)) + "…";
    }

    private Map<String, Object> item(String label, int value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        return item;
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    private String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = duration.toHours();
        return hours < 24 ? hours + " 小时前" : duration.toDays() + " 天前";
    }
}
