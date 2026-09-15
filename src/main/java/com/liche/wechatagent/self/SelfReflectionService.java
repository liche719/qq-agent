package com.liche.wechatagent.self;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.config.LlmScenario;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 反思流程（spec §4）：主 agent 不在关键路径上时，由**另一个更便宜的调用**整合状态。
 *
 * <p>四条先例给的纪律（照做，别简化掉）：
 * <ol>
 *   <li><b>触发不是"每晚一次"</b>：档位 `off` / `step-count`（攒够 N 轮）/ 手动。
 *       定时器只负责"到点看看攒够没有"，真正的判据是 {@link #turnsSinceLastReflection()}。</li>
 *   <li><b>进程要窄</b>：这里**没有工具**——它只能读记录、写自己那侧，写还全都走 {@link SelfCoreService} 的校验。</li>
 *   <li><b>只写可 diff 的状态</b>：结论进 {@code agent_reflection}，块改动走事件（旧值→新值都留痕），
 *       所以一切可回溯。</li>
 *   <li><b>失败丢整条</b>：模型输出解析不出来就**不写半成品**（只记 WARN 与成本），下次再说。</li>
 * </ol>
 *
 * <p>**倾向是规则不是提示词**：{@link #applyStances} 纯按 {@link StancePromoter} 的判据办事，
 * 不需要模型参与，所以它既可以跟着反思跑，也能单独触发来验证（"该提没提 / 不该提却提了"）。
 */
@Service
public class SelfReflectionService {

    private static final Logger log = LoggerFactory.getLogger(SelfReflectionService.class);

    /** 一次反思的结果（面板与日志用） */
    public record Outcome(boolean ran, String reason, Long reflectionId, String conclusion,
                          StanceOutcome stances, int commitmentsBroken, int lessonsAdded, int lessonsRecurred,
                          int lessonsReviewed, int promptTokens, int completionTokens) {

        static Outcome skipped(String reason, StanceOutcome stances) {
            return new Outcome(false, reason, null, null, stances, 0, 0, 0, 0, 0, 0);
        }
    }

    public record StanceOutcome(int promoted, int revised, int supported, int contradicted, int demoted) {

        static final StanceOutcome NONE = new StanceOutcome(0, 0, 0, 0, 0);

        public boolean touched() {
            return promoted + revised + supported + contradicted + demoted > 0;
        }

        @Override
        public String toString() {
            return "立 " + promoted + " / 修订 " + revised + " / 支撑 " + supported
                    + " / 反例 " + contradicted + " / 降级 " + demoted;
        }
    }

    private final SelfCoreService core;
    private final SelfStanceService selfStances;
    private final SelfLessonService selfLessons;
    private final AgentReflectionRepository reflectionRepository;
    private final ConversationMemoryRepository conversationMemoryRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final int dailyCallLimit;
    private final int minImportance;
    private final int maxInputEvents;
    private final int maxPromptChars;
    private final int minIntervalMinutes;
    private final int staleStanceDays;
    private final int judgeScan;
    private final int maxStancesInContent;
    private final StancePromoter.Params params;

    public SelfReflectionService(SelfCoreService core,
                                 SelfStanceService selfStances,
                                 SelfLessonService selfLessons,
                                 AgentReflectionRepository reflectionRepository,
                                 ConversationMemoryRepository conversationMemoryRepository,
                                 ObjectProvider<ChatModel> chatModelProvider,
                                 ObjectMapper objectMapper,
                                 @Value("${memory.self-reflect-daily-call-limit:4}") int dailyCallLimit,
                                 @Value("${memory.self-reflect-min-importance:2}") int minImportance,
                                 @Value("${memory.self-reflect-max-input-events:40}") int maxInputEvents,
                                 @Value("${memory.self-reflect-max-prompt-chars:6000}") int maxPromptChars,
                                 @Value("${memory.self-reflect-min-interval-minutes:30}") int minIntervalMinutes,
                                 @Value("${memory.self-stance-stale-days:60}") int staleStanceDays,
                                 @Value("${memory.self-stance-scan:200}") int judgeScan,
                                 @Value("${memory.self-stance-min-evidence:3}") int minEvidence,
                                 @Value("${memory.self-stance-min-days:2}") int minDays,
                                 @Value("${memory.self-stance-min-contexts:2}") int minContexts,
                                 @Value("${memory.self-stance-threshold:1.2}") double threshold,
                                 @Value("${memory.self-stance-half-life-days:30}") double halfLifeDays,
                                 @Value("${memory.self-fsrs-decay:-0.1542}") double decay,
                                 @Value("${memory.self-review-target:0.8}") double reviewTarget) {
        this.core = core;
        this.selfStances = selfStances;
        this.selfLessons = selfLessons;
        this.reflectionRepository = reflectionRepository;
        this.conversationMemoryRepository = conversationMemoryRepository;
        this.chatModel = chatModelProvider == null ? null : chatModelProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.dailyCallLimit = Math.max(1, dailyCallLimit);
        this.minImportance = Math.max(0, minImportance);
        this.maxInputEvents = Math.max(1, maxInputEvents);
        this.maxPromptChars = Math.max(500, maxPromptChars);
        this.minIntervalMinutes = Math.max(0, minIntervalMinutes);
        this.staleStanceDays = Math.max(1, staleStanceDays);
        this.judgeScan = Math.max(10, judgeScan);
        this.maxStancesInContent = 6;
        this.params = new StancePromoter.Params(Math.max(2, minEvidence), Math.max(1, minDays),
                Math.max(1, minContexts), Math.max(0.1, threshold), Math.max(1, halfLifeDays),
                decay > 0 ? -decay : (decay == 0 ? -0.1542 : decay),
                Math.min(0.98, Math.max(0.5, reviewTarget)));
    }

    /** 手动/排障入口：不走"攒够轮数"的判据，直接跑一次（预算仍然生效）。 */
    public Outcome reflect(String userId, String trigger) {
        if (!core.isOwner(userId)) {
            return Outcome.skipped(core.inactiveReason(), StanceOutcome.NONE);
        }
        LocalDateTime now = LocalDateTime.now();
        // ① 倾向维护是**规则**，不花调用、也不该被模型输出影响，所以放在最前面独立跑
        StanceOutcome stances = applyStances(now);

        // ② 到期未兑现 → BROKEN（"得失"的落点）：也不需要模型
        int broken = settleOverdueCommitments(now);

        // 教训复查同样是**程序驱动**：模型不能给自己点赞（spec §9.1 硬规则 4）
        int lessonsReviewed = selfLessons.reviewLessons(now);

        // ③ 合成反思要花钱：先过预算、防抖与重要度三道闸
        if (reflectionsToday() >= dailyCallLimit) {
            return Outcome.skipped("今天的反思预算用完了（" + dailyCallLimit + " 次）", stances);
        }
        Optional<AgentReflection> last = lastReflection();
        if (last.isPresent() && last.get().getCreatedAt() != null && minIntervalMinutes > 0) {
            long minutes = java.time.Duration.between(last.get().getCreatedAt(), now).toMinutes();
            if (minutes < minIntervalMinutes) {
                // 防抖（spec §13.2 明确要求）：定时任务与手动触发可能前后脚到，别对同一批内容重复花调用
                return Outcome.skipped("距上次反思才 " + Math.max(0, minutes) + " 分钟（防抖间隔 "
                        + minIntervalMinutes + " 分钟）", stances);
            }
        }
        LocalDateTime since = last.map(AgentReflection::getCreatedAt).orElse(now.minusDays(2));
        List<AgentSelfEvent> events = core.eventsSince(since).stream()
                // 不看**自己的结论**（REFLECT）和**自己写下的教训**（LESSON）：
                // 否则会从自己的记录里再推导一条同类教训 —— 那就是流水账/回音室（spec §9.1 硬规则 1）。
                // 教训应该从**行为痕迹**（判断/承诺/目标/随手记）里发现，而不是从教训里再长出教训。
                .filter(event -> !AgentSelfEvent.KIND_REFLECT.equals(event.getKind())
                        && !AgentSelfEvent.KIND_LESSON.equals(event.getKind()))
                .sorted(Comparator.comparingInt((AgentSelfEvent event) ->
                                event.getImportance() == null ? 0 : event.getImportance()).reversed()
                        .thenComparing(AgentSelfEvent::getId))
                .limit(maxInputEvents)
                .toList();
        if (events.isEmpty()) {
            return Outcome.skipped("自上次反思以来没有新事件", stances);
        }
        boolean worthSynthesizing = events.stream()
                .anyMatch(event -> (event.getImportance() == null ? 0 : event.getImportance()) >= minImportance);
        if (!worthSynthesizing) {
            return Outcome.skipped("只有低重要度的流水，不值得花一次调用", stances);
        }
        if (chatModel == null) {
            return Outcome.skipped("没有可用的模型", stances);
        }

        // ④ 一次窄调用：只让它写一句结论 +（可选）改写「我现在在做」
        List<Long> inputIds = events.stream().map(AgentSelfEvent::getId).toList();
        String prompt = buildPrompt(events, now);
        long started = System.nanoTime();
        String response;
        ChatResponse chatResponse = null;
        try {
            ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build();
            chatResponse = LlmScenario.run(LlmScenario.REFLECT, () -> chatModel.chat(request));
            response = chatResponse.aiMessage() == null ? null : chatResponse.aiMessage().text();
        } catch (RuntimeException exception) {
            log.warn("反思调用失败：{}", exception.getMessage());
            return Outcome.skipped("反思调用失败：" + exception.getMessage(), stances);
        }
        int durationMs = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        TokenUsage usage = chatResponse == null ? null : chatResponse.tokenUsage();
        int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

        Synthesis synthesis = parse(response);
        if (synthesis == null) {
            // spec §4：失败就丢弃本次反思，**不写半成品**
            log.warn("反思输出无法解析，本次丢弃（tokens={}/{}，正文 {} 字）：{}", promptTokens, completionTokens,
                    response == null ? 0 : response.length(), SelfText.clipLine(response, 200));
            return Outcome.skipped("反思输出无法解析，本次丢弃", stances);
        }

        Long writtenBack = null;
        if (synthesis.taskBlock() != null && !synthesis.taskBlock().isBlank()) {
            try {
                AgentSelfBlock block = core.setBlockValue(AgentSelfBlock.TYPE_TASK,
                        synthesis.taskBlock(), "event:" + inputIds.get(0));
                writtenBack = block.getId();
            } catch (RuntimeException exception) {
                log.warn("反思写回块失败（结论仍然保留）：{}", exception.getMessage());
            }
        }
        AgentReflection reflection = recordReflection(1, trigger, inputIds, synthesis.conclusion(),
                synthesis.importance(), writtenBack, 1, prompt.length(),
                response == null ? 0 : response.length(), promptTokens, completionTokens, durationMs);
        LessonTally tally = applyLessons(synthesis, inputIds);
        log.info("自主模块反思完成 id={} trigger={} 事件 {} 条 倾向[{}] 承诺判欠 {} 条 教训(新{} 又犯{} 复查{}) tokens={}/{}",
                reflection.getId(), trigger, inputIds.size(), stances, broken, tally.added(), tally.recurred(),
                lessonsReviewed, promptTokens, completionTokens);
        return new Outcome(true, null, reflection.getId(), synthesis.conclusion(), stances, broken,
                tally.added(), tally.recurred(), lessonsReviewed, promptTokens, completionTokens);
    }

    /** 只跑倾向维护（不花模型调用）——"该提没提 / 不该提却提了"就用它单独验证。 */
    public StanceOutcome applyStances(LocalDateTime now) {
        int promoted = 0;
        int revised = 0;
        int supported = 0;
        int contradicted = 0;
        for (String topic : selfStances.judgeTopics(judgeScan)) {
            try {
                List<AgentSelfEvent> judges = selfStances.judgesFor(topic);
                if (judges.isEmpty()) {
                    continue;
                }
                Optional<AgentStance> active = selfStances.stanceFor(topic);
                List<StancePromoter.Judge> mapped = judges.stream()
                        .map(event -> new StancePromoter.Judge(event.getId(), event.getStance(),
                                event.getCreatedAt(), contextKey(event)))
                        .toList();
                StancePromoter.Decision decision = StancePromoter.evaluate(mapped,
                        active.map(AgentStance::getDirection).orElse(null),
                        active.map(AgentStance::getFormedAt).orElse(null), params, now);
                switch (decision.action()) {
                    case PROMOTE -> {
                        String content = stanceContent(topic, decision, judges);
                        if (content == null) {
                            continue;
                        }
                        selfStances.promoteStance(topic, decision.direction(), content, decision.evidenceIds(),
                                decision.counterIds(), lastEvidence(decision.evidenceIds()));
                        promoted++;
                    }
                    case REVISE -> {
                        String content = stanceContent(topic, decision, judges);
                        if (active.isEmpty() || content == null) {
                            continue;
                        }
                        selfStances.reviseStance(active.get().getId(), decision.direction(), content,
                                decision.evidenceIds(), decision.counterIds(), lastEvidence(decision.evidenceIds()));
                        revised++;
                    }
                    case HOLD -> {
                        if (active.isEmpty()) {
                            log.debug("倾向未达标 topic={} reason={}", topic, decision.reason());
                            continue;
                        }
                        AgentStance stance = active.get();
                        List<Long> known = parseIds(stance.getEvidenceIds());
                        List<Long> knownCounter = parseIds(stance.getCounterIds());
                        List<Long> sameNew = judges.stream()
                                .filter(event -> matches(stance.getDirection(), event.getStance()))
                                .map(AgentSelfEvent::getId)
                                .filter(id -> !known.contains(id))
                                .toList();
                        List<Long> counterNew = judges.stream()
                                .filter(event -> !matches(stance.getDirection(), event.getStance()))
                                .map(AgentSelfEvent::getId)
                                .filter(id -> !knownCounter.contains(id))
                                .toList();
                        if (!sameNew.isEmpty()) {
                            selfStances.supportStance(stance.getId(), sameNew, lastEvidence(sameNew));
                            supported++;
                        }
                        if (!counterNew.isEmpty()) {
                            selfStances.contradictStance(stance.getId(), counterNew, lastEvidence(counterNew));
                            contradicted++;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                // 上限、并发等：跳过这一类，不影响其它类别（反思不能因为一条倾向失败而整体失败）
                log.warn("倾向维护跳过 topic={} reason={}", topic, exception.getMessage());
            }
        }
        int demoted = selfStances.demoteStaleStances(staleStanceDays, now);
        return new StanceOutcome(promoted, revised, supported, contradicted, demoted);
    }

    /** 把这次反思发现的教训落库（三段齐 + 证据真实，SelfLessonService 会再校验一次） */
    private LessonTally applyLessons(Synthesis synthesis, List<Long> inputIds) {
        if (synthesis.lessons() == null || synthesis.lessons().isEmpty()) {
            return new LessonTally(0, 0);
        }
        String evidence = "event:" + inputIds.get(inputIds.size() - 1);
        int added = 0;
        int recurred = 0;
        for (LessonCandidate candidate : synthesis.lessons()) {
            try {
                SelfLessonService.LessonOutcome outcome = selfLessons.addLesson(candidate.category(), candidate.trigger(),
                        candidate.whatIDid(), candidate.expected(), candidate.whatHappened(),
                        candidate.correction(), evidence);
                if (outcome.recurred()) {
                    recurred++;
                } else {
                    added++;
                }
            } catch (RuntimeException exception) {
                // 清单到上限 / 三段不齐 / 证据对不上：跳过这一条，不能让整次反思失败
                log.warn("教训没记进去（{}）：{}", candidate.category(), exception.getMessage());
            }
        }
        return new LessonTally(added, recurred);
    }
    // ---------------------------------------------------------------- 内部

    private int settleOverdueCommitments(LocalDateTime now) {
        int broken = 0;
        for (AgentCommitment commitment : core.dueOpenCommitments(now)) {
            String evidence = usableEvidence(commitment.getEvidence());
            if (evidence == null) {
                log.warn("承诺 #{} 到期但找不到可用证据，跳过判欠", commitment.getId());
                continue;
            }
            try {
                core.resolveCommitment(commitment.getId(), AgentCommitment.STATUS_BROKEN, evidence);
                broken++;
            } catch (RuntimeException exception) {
                log.warn("承诺 #{} 判欠失败：{}", commitment.getId(), exception.getMessage());
            }
        }
        return broken;
    }

    /** 事件自带的证据：形如 `conv:12` / `event:7`（多个用逗号分隔）才算数 */
    private String usableEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return null;
        }
        for (String token : evidence.split("[,，;；\\s]+")) {
            String trimmed = token.trim();
            if (trimmed.startsWith(SelfCoreService.EVIDENCE_CONVERSATION)
                    || trimmed.startsWith(SelfCoreService.EVIDENCE_EVENT)) {
                return trimmed;
            }
        }
        return null;
    }

    private String lastEvidence(List<Long> ids) {
        return "event:" + ids.get(ids.size() - 1);
    }

    private boolean matches(String direction, String stance) {
        String left = direction == null ? "" : direction.trim();
        String right = stance == null ? "" : stance.trim();
        return left.equalsIgnoreCase(right);
    }

    /**
     * 情境（spec §5 的"跨 ≥2 个不同情境"）：**按小时分桶**——
     * 同一段对话里反复说只算 1 次，所以不能按条数直接累加。
     */
    String contextKey(AgentSelfEvent event) {
        LocalDateTime at = event.getCreatedAt();
        if (at == null) {
            return "unknown";
        }
        return at.toLocalDate() + "T" + at.getHour();
    }

    /** 倾向原话：用同向判断里最新的一条，并带上证据区间（§5 硬要求） */
    private String stanceContent(String topic, StancePromoter.Decision decision, List<AgentSelfEvent> judges) {
        Optional<AgentSelfEvent> latest = judges.stream()
                .filter(event -> decision.evidenceIds().contains(event.getId()))
                .max(Comparator.comparing(AgentSelfEvent::getId));
        if (latest.isEmpty()) {
            return null;
        }
        List<Long> ids = decision.evidenceIds();
        List<Long> shown = ids.size() <= maxStancesInContent ? ids
                : ids.subList(ids.size() - maxStancesInContent, ids.size());
        StringBuilder content = new StringBuilder("在「").append(topic).append("」这类事上我一贯：")
                .append(latest.get().getContent())
                .append("（依据 event ").append(shown.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("、"))).append("）");
        if (decision.counterScore() > 0) {
            content.append("；另有反例（权重 ").append(round(decision.counterScore())).append("）");
        }
        return content.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private record Synthesis(String conclusion, int importance, String taskBlock, List<LessonCandidate> lessons) {
    }

    /** 反思顺带发现的教训（三段齐才落库；投票不在这里，由程序按后续真实事件判定） */
    private record LessonCandidate(String category, String trigger, String whatIDid, String expected,
                                  String whatHappened, String correction) {
    }

    private record LessonTally(int added, int recurred) {
    }

    /** 解析模型输出：只认 JSON；带代码块围栏也容忍；解析不出来返回 null（调用方丢弃整条） */
    Synthesis parse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String text = response.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                text = text.substring(firstBreak + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            String conclusion = root.path("conclusion").asText("").trim();
            if (conclusion.isEmpty()) {
                return null;
            }
            if (conclusion.length() > 1000) {
                conclusion = conclusion.substring(0, 999) + "…";
            }
            int importance = Math.min(5, Math.max(1, root.path("importance").asInt(1)));
            String taskBlock = root.path("task_block").asText("").trim();
            List<LessonCandidate> lessons = new ArrayList<>();
            JsonNode lessonsNode = root.path("lessons");
            if (lessonsNode.isArray()) {
                for (JsonNode node : lessonsNode) {
                    if (lessons.size() >= 2) {
                        break;
                    }
                    String lessonCategory = node.path("category").asText("").trim().toUpperCase(Locale.ROOT);
                    String lessonCorrection = node.path("correction").asText("").trim();
                    if (lessonCategory.isEmpty() || lessonCorrection.isEmpty()) {
                        continue;
                    }
                    lessons.add(new LessonCandidate(lessonCategory, node.path("trigger").asText("").trim(),
                            node.path("what_i_did").asText("").trim(), node.path("expected").asText("").trim(),
                            node.path("what_happened").asText("").trim(), lessonCorrection));
                }
            }
            return new Synthesis(conclusion, importance, taskBlock, lessons);
        } catch (Exception exception) {
            log.debug("反思输出解析失败：{}", exception.getMessage());
            return null;
        }
    }

    private List<Long> parseIds(String ids) {
        List<Long> parsed = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return parsed;
        }
        for (String token : ids.split(",")) {
            try {
                parsed.add(Long.valueOf(token.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过
            }
        }
        return parsed;
    }

    private String buildPrompt(List<AgentSelfEvent> events, LocalDateTime now) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「它自己」那一侧的整合者，只对\"我自己\"负责。下面是我最近的记录。\n\n");
        prompt.append("【最近的事件】(id | 类型 | 类别 | 方向 | 重要度 | 内容)\n");
        for (AgentSelfEvent event : events) {
            prompt.append(event.getId()).append(" | ").append(event.getKind()).append(" | ")
                    .append(nullToDash(event.getTopic())).append(" | ")
                    .append(nullToDash(event.getStance())).append(" | ")
                    .append(event.getImportance() == null ? 0 : event.getImportance()).append(" | ")
                    .append(SelfText.clipLine(event.getContent(), 160)).append('\n');
            if (prompt.length() > maxPromptChars) {
                prompt.append("…（已截断）\n");
                break;
            }
        }
        List<AgentSelfBlock> blocks = core.blocks();
        if (!blocks.isEmpty()) {
            prompt.append("\n【我现在的状态块】\n");
            for (AgentSelfBlock block : blocks) {
                prompt.append(block.getBlockType()).append("：")
                        .append(SelfText.clipLine(block.getValue(), 200)).append('\n');
            }
        }
        List<AgentStance> stances = selfStances.activeStances();
        if (!stances.isEmpty()) {
            prompt.append("\n【我已经形成的倾向】\n");
            for (AgentStance stance : stances) {
                prompt.append("· ").append(SelfText.clipLine(stance.getContent(), 200)).append('\n');
            }
        }
        List<AgentCommitment> open = core.openCommitments();
        if (!open.isEmpty()) {
            prompt.append("\n【我还欠着的】\n");
            for (AgentCommitment commitment : open) {
                prompt.append("· ").append(SelfText.clipLine(commitment.getContent(), 120))
                        .append(commitment.getDueAt() == null ? "" : "（截止 " + commitment.getDueAt().toLocalDate() + "）")
                        .append('\n');
            }
        }
        prompt.append("\n现在是 ").append(now.toLocalDate()).append(" ").append(now.getHour()).append(" 点。\n");
        prompt.append("请只做一件事：用**不超过 120 字**写清「这段时间我这边发生了什么、我现在在做的事要不要改」。\n");
        prompt.append("不要写感悟、不要总结机主、不要立新承诺、不要复述上面的原文。\n");
        prompt.append("只输出 JSON（不要代码块、不要解释）：\n");
        prompt.append("{\"conclusion\":\"…\",\"importance\":1,\"task_block\":\"\",\"lessons\":[]}\n");
        prompt.append("lessons：这段时间若有**你预期落空**的事（时间算错/答应了没做/没核就答/格式返工/工具用错），");
        prompt.append("每条写全——category（TIME/COMMITMENT/GUESS/FORMAT/TOOL）、trigger（SURPRISE/USER_POINTED/PROMISE_BROKEN/SELF_CHECK）、");
        prompt.append("what_i_did（我做了什么）、expected（我当时预期）、what_happened（实际发生）、correction（以后怎么做的**可执行短句**）。");
        prompt.append("**不要写感悟**（\"以后要更细心\"这种没用），最多 2 条；没有就给空数组。\n");
        prompt.append("task_block：认为「我现在在做」该改写时给出改写后的**完整全文**（≤300 字）；不需要改就留空字符串。\n");
        return prompt.toString();
    }

    private String nullToDash(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }

    // ---------------------------------------------------------------- 反思记录（这一侧的账）

    @Transactional(readOnly = true)
    public Optional<AgentReflection> lastReflection() {
        return reflectionRepository.findTop1ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<AgentReflection> recentReflections(int limit) {
        return reflectionRepository.findTop50ByOrderByIdDesc().stream().limit(Math.max(1, limit)).toList();
    }

    @Transactional(readOnly = true)
    public long reflectionsToday() {
        return reflectionRepository.countByCreatedAtAfter(LocalDate.now().atStartOfDay());
    }

    /** 距上次反思以来机主说了多少轮（"攒够 N 轮"的判据）。 */
    @Transactional(readOnly = true)
    public long turnsSinceLastReflection() {
        if (!core.isActive()) {
            return 0;
        }
        LocalDateTime since = lastReflection().map(AgentReflection::getCreatedAt).orElse(null);
        return conversationMemoryRepository
                .findByUserIdAndRoleInOrderByCreatedAtDesc(core.owner(), List.of("user"), PageRequest.of(0, 200))
                .stream()
                .filter(row -> since == null || (row.getCreatedAt() != null && row.getCreatedAt().isAfter(since)))
                .count();
    }

    /**
     * 兴趣累积：自上次反思以来**它自己**产生的事件按重要度之和。
     *
     * <p>这是"不等机主说话也会想"的判据（Generative Agents 用的就是 importance 累加过阈值，
     * 不是"机主说了多少句"）。用机主的消息量当触发，它的思考就变成机主对话量的影子——
     * 而这个模块要的是**它自己的事占大头**。
     */
    @Transactional(readOnly = true)
    public int interestSinceLastReflection() {
        LocalDateTime since = lastReflection().map(AgentReflection::getCreatedAt)
                .orElse(LocalDateTime.now().minusDays(2));
        return core.eventsSince(since).stream()
                .filter(event -> !AgentSelfEvent.KIND_REFLECT.equals(event.getKind()))
                .mapToInt(event -> event.getImportance() == null ? 0 : event.getImportance())
                .sum();
    }

    /**
     * 反思落库。**必须带证据链**（读了哪几条事件），否则整条拒绝——
     * 对应 spec §4 的"合成失败就丢弃，不写半成品"。
     */
    @Transactional
    public AgentReflection recordReflection(int level, String trigger, List<Long> inputEventIds, String conclusion,
                                            int importance, Long writtenBack, int calls, int promptChars,
                                            int responseChars, int promptTokens, int completionTokens,
                                            int durationMs) {
        if (inputEventIds == null || inputEventIds.isEmpty()) {
            throw new IllegalArgumentException("反思必须带证据链（inputEventIds 不能为空）");
        }
        String evidence = inputEventIds.stream().map(id -> "event:" + id).collect(Collectors.joining(","));
        core.requireEvidence(evidence);
        AgentReflection reflection = new AgentReflection();
        reflection.setLevel(Math.max(1, level));
        reflection.setTriggerType(SelfText.clip(trigger, 16));
        reflection.setInputEventIds(SelfText.clip(evidence, 500));
        reflection.setConclusion(SelfText.clip(conclusion, 1000));
        reflection.setImportance(importance);
        reflection.setWrittenBack(writtenBack);
        reflection.setCalls(Math.max(1, calls));
        reflection.setPromptChars(Math.max(0, promptChars));
        reflection.setResponseChars(Math.max(0, responseChars));
        reflection.setPromptTokens(Math.max(0, promptTokens));
        reflection.setCompletionTokens(Math.max(0, completionTokens));
        reflection.setDurationMs(Math.max(0, durationMs));
        reflection.setCreatedAt(LocalDateTime.now());
        AgentReflection saved = reflectionRepository.save(reflection);
        core.appendEvent(AgentSelfEvent.KIND_REFLECT, saved.getConclusion(), "event:" + inputEventIds.get(0),
                null, null, importance);
        return saved;
    }
}
