package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.log.UserScope;
import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.config.LlmScenario;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final String FENCE = "\u0060\u0060\u0060";
    private static final int DEFAULT_PRIORITY = 3;

    /**
     * 新核心事实与已有核心记忆的相似度超过它 → 判定为"同一个事实的新版本"，走替换（旧条目 SUPERSEDED）而不是新增。
     * 0.72 是在「考研数学目标分是130 / 目标分改成140」（≈0.75）与「不喜欢咖啡 / 喜欢喝茶」（≈0.2）之间取的。
     */
    private static final double CORE_CONFLICT_SIMILARITY = 0.72d;

    /** 核心记忆超过这么多条时，就不做"这条是不是某条的新版本"的二次判断了（太多了模型也判不准） */
    private static final int RECONCILE_MAX_CORES = 60;

    private final ChatModel chatModel;
    private final ContextStore contextStore;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final int recentTurns;
    private final StoredMediaRepository storedMediaRepository;
    private final MemoryMutationLock mutationLock;
    private final ConversationMemoryService conversationMemoryService;
    private final int minConfidence;
    private final int maxCandidateItems;
    private final int maxContentChars;
    private final int maxKeywords;
    private final int defaultConfidence;
    private final int defaultPriority;
    private final int defaultCoreImportance;
    private final int defaultWorkImportance;
    private final double dedupThreshold;
    private final ZoneId zone;
    /** 提取审计（2026-09-17）：每跑一次留一行；可为 null（单测直接构造） */
    private MemoryExtractionAudit audit;

    @Autowired(required = false)
    public void setAudit(MemoryExtractionAudit audit) {
        this.audit = audit;
    }

    /**
     * 事实层（2026-09-18）：会变的信息（课表/教室/时间…）写这里，见 {@link MemoryFactService}。没装配就整块跳过
     */
    private MemoryFactService factService;

    @Autowired(required = false)
    public void setFactService(MemoryFactService factService) {
        this.factService = factService;
    }

    /**
     * 上下文重叠：窗口里**最老的这几条机主消息只作背景**（上次提取已经看过）。
     * 为什么需要：触发按"攒够 15 轮"来，窗口边界会切在话说到一半的地方——"那改成 305 吧"被切到下一窗，
     * 模型就不知道在改哪件事。多留几条作背景，跨窗口的话才读得懂；标出来又不会被反复提取成新记忆。
     *
     * <p><b>具体标几条不是拍脑袋定的</b>：按"这一窗真正新增了几条"（{@code burstTexts} 是上一轮提取之后
     * 用户说过的话）算——窗口里 20 条、其中 15 条是新的 → 最老 5 条标背景。
     * **首次提取（没有 burst 信息）一条都不标**：那时候没有任何"上次已经处理过"的消息，
     * 标了就等于把最老的几条永久漏掉。
     */
    private int overlapTurns = 5;

    @Value("${memory.extraction-overlap-turns:5}")
    public void setOverlapTurns(int overlapTurns) {
        this.overlapTurns = Math.max(0, Math.min(20, overlapTurns));
    }

    /** 轮次触发的条数（与调度器同一个配置）：用来推导"这一趟该读多长的窗口"，见 {@link #windowLimit} */
    private int roundsForWindow = 15;

    @Value("${memory.extraction-rounds:15}")
    public void setRoundsForWindow(int roundsForWindow) {
        this.roundsForWindow = Math.max(1, roundsForWindow);
    }

    /** 窗口行数的显式覆盖（{@code memory.extraction-window-rows}）：0 = 按 ROUNDS 自动推导 */
    private int windowRowsOverride = 0;

    @Value("${memory.extraction-window-rows:0}")
    public void setWindowRowsOverride(int windowRowsOverride) {
        this.windowRowsOverride = Math.max(0, windowRowsOverride);
    }

    /**
     * 这一趟要读多长的窗口（对话行数）：**跟着"轮次触发条数"走**，不写死。
     *
     * <p>窗口 = (ROUNDS + 重叠) × 2 行，重叠 = **min(上限, ⌈ROUNDS×0.3⌉)**：
     * <ul>
     *   <li>15 轮 → 重叠 min(5, 5) = 5 → (15+5)×2 = **40 行**（就是原来那个数）；</li>
     *   <li>30 轮 → 重叠 min(5, 9) = **5**（用户 2026-09-18 加的约束：重叠最多 5 轮，不能随轮次无限涨）→ 70 行。</li>
     * </ul>
     * 写死的坏处很实在：把 ROUNDS 调到 25，窗口还是 40 行的话，**触发它的那 25 条里最老的 5 条会落在窗口外、静默漏记**。
     *
     * <p>另外：新消息比它多时继续放大（一口气说很多条、或提取被推迟），上限由
     * {@code memory.conversation-extraction-limit}（默认 80 行）兜住。
     * 想手工钉死就配 {@code memory.extraction-window-rows}（正数覆盖推导值）。
     */
    private int windowLimit(List<String> burstTexts) {
        int newTurns = burstTexts == null ? 0 : burstTexts.size();
        // 重叠最多 overlapTurns 轮（默认 5）：rounds×0.3 超过它就取它
        int overlap = Math.min(overlapTurns, (int) Math.ceil(roundsForWindow * 0.3));
        int base = windowRowsOverride > 0 ? windowRowsOverride : (roundsForWindow + overlap) * 2;
        return Math.max(base, Math.min(400, newTurns * 2 + 10));
    }

    /** 已有事实卡片清单最多列多少张 / 渲染上限多少字（超过就不列，退回召回+判定） */
    private int factPromptCards = 30;
    private int factPromptCardsMaxChars = 4000;

    @Value("${memory.fact-prompt-cards:30}")
    public void setFactPromptCards(int factPromptCards) {
        this.factPromptCards = Math.max(0, Math.min(200, factPromptCards));
    }

    @Value("${memory.fact-prompt-cards-max-chars:4000}")
    public void setFactPromptCardsMaxChars(int factPromptCardsMaxChars) {
        this.factPromptCardsMaxChars = Math.max(500, factPromptCardsMaxChars);
    }

    /** 该把最老的几条机主消息标成"背景"（0 = 一条都不标） */
    private int backgroundUserTurns(List<ContextTurn> recent, int newTurns) {
        if (overlapTurns <= 0 || newTurns <= 0 || recent == null || recent.isEmpty()) {
            return 0;
        }
        int userTurns = 0;
        for (ContextTurn turn : recent) {
            if (turn != null && "user".equals(turn.role())) {
                userTurns++;
            }
        }
        return Math.min(overlapTurns, Math.max(0, userTurns - newTurns));
    }

    @Autowired
    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           MemoryService memoryService,
                           ObjectMapper objectMapper,
                           @Value("${memory.extraction-recent-turns:40}") int recentTurns,
                           StoredMediaRepository storedMediaRepository,
                           MemoryMutationLock mutationLock,
                           ConversationMemoryService conversationMemoryService,
                           @Value("${memory.min-confidence:60}") int minConfidence,
                           MemoryPolicyProperties policyProperties,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.chatModel = chatModel;
        this.contextStore = contextStore;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
        this.storedMediaRepository = storedMediaRepository;
        this.mutationLock = mutationLock;
        this.conversationMemoryService = conversationMemoryService;
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        int requestedRecentTurns = Math.max(1, recentTurns);
        int configuredRecentTurns = policies.getExtractionRecentTurns();
        int effectiveRecentTurns = configuredRecentTurns == MemoryPolicyProperties.DEFAULT_EXTRACTION_RECENT_TURNS
                && requestedRecentTurns != MemoryPolicyProperties.DEFAULT_EXTRACTION_RECENT_TURNS
                ? requestedRecentTurns : configuredRecentTurns;
        this.recentTurns = bounded(effectiveRecentTurns, 1, 256, requestedRecentTurns);
        int requestedMinConfidence = Math.max(0, Math.min(100, minConfidence));
        int configuredMinConfidence = policies.getMinConfidence();
        int effectiveMinConfidence = configuredMinConfidence == MemoryPolicyProperties.DEFAULT_MIN_CONFIDENCE
                && requestedMinConfidence != MemoryPolicyProperties.DEFAULT_MIN_CONFIDENCE
                ? requestedMinConfidence : configuredMinConfidence;
        this.minConfidence = bounded(effectiveMinConfidence, 0, 100, requestedMinConfidence);
        this.maxCandidateItems = bounded(policies.getExtractionMaxCandidates(), 1, 256,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_MAX_CANDIDATES);
        this.maxContentChars = bounded(policies.getExtractionMaxContentChars(), 128, 100_000,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_MAX_CONTENT_CHARS);
        this.maxKeywords = bounded(policies.getExtractionMaxKeywords(), 1, 32,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_MAX_KEYWORDS);
        this.defaultConfidence = bounded(policies.getExtractionDefaultConfidence(), 0, 100,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE);
        this.defaultPriority = bounded(policies.getDefaultWorkPriority(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_PRIORITY);
        this.defaultCoreImportance = bounded(policies.getDefaultCoreImportance(), 1, 5,
                MemoryPolicyProperties.DEFAULT_CORE_IMPORTANCE);
        this.defaultWorkImportance = bounded(policies.getDefaultWorkImportance(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_IMPORTANCE);
        this.dedupThreshold = bounded(policies.getDedupThreshold(), 0.60d, 0.98d, 0.80d);
        this.zone = parseZone(timeZoneId);
    }

    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           MemoryService memoryService,
                           ObjectMapper objectMapper,
                           int recentTurns,
                           StoredMediaRepository storedMediaRepository,
                           MemoryMutationLock mutationLock) {
        this(chatModel, contextStore, memoryService, objectMapper,
                recentTurns, storedMediaRepository, mutationLock, null, 60,
                new MemoryPolicyProperties(), "Asia/Shanghai");
    }

    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           MemoryService memoryService,
                           ObjectMapper objectMapper,
                           int recentTurns) {
        this(chatModel, contextStore, memoryService, objectMapper,
                recentTurns, null, new MemoryMutationLock(), null, 60,
                new MemoryPolicyProperties(), "Asia/Shanghai");
    }

    public boolean extract(String userId) {
        return extract(userId, () -> true);
    }

    /**
     * 只允许仍属于当前静默窗口的提取结果写回。模型调用无法强制取消，
     * 但在持久化前再次核验即可避免旧会话覆盖新事实。
     */
    public boolean extract(String userId, BooleanSupplier stillCurrent) {
        return extract(userId, List.of(), stillCurrent);
    }

    public boolean extract(String userId, List<String> burstTexts, BooleanSupplier stillCurrent) {
        return extract(userId, burstTexts, stillCurrent, MemoryExtractionRun.TRIGGER_AUTO);
    }

    /**
     * @param burstTexts 这一轮静默窗口里**用户说过的话**（判"事务型窗口"用，规则见
     *                   docs/memory-hybrid-plan.md §4.3.1）；空列表时退回"整窗判定"（保守：判不了就不挡）
     * @param trigger    触发来源（AUTO / MANUAL）：只进审计——面板「提取记录」的"触发"列要如实显示，
     *                   手动点的那次记成"自动"会让人以为是系统自己跑的
     */
    public boolean extract(String userId, List<String> burstTexts, BooleanSupplier stillCurrent, String trigger) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        BooleanSupplier currentCheck = stillCurrent == null ? () -> true : stillCurrent;
        MDC.put("userScope", UserScope.forUser(userId));
        MemoryExtractionAudit.Span span = audit == null ? null : audit.begin();
        // [0]=verdict（模型提议了多少条） [1]=reason（跳过原因） [2]=实际落库清单
        // ——用数组是为了能在下面的 lambda 里赋值
        String[] trail = new String[]{null, null, null};
        int windowTurns = 0;
        int windowChars = 0;
        try {
            if (!currentCheck.getAsBoolean()) {
                trail[1] = "STALE";
                return true;
            }
            List<ContextTurn> recent = conversationMemoryService == null
                    ? List.of() : conversationMemoryService.recentForExtraction(userId, windowLimit(burstTexts));
            if (recent.isEmpty()) {
                recent = contextStore.getRecent(userId, recentTurns);
            }
            if (recent.isEmpty()) {
                trail[1] = "WINDOW_EMPTY";
                return true;
            }
            windowTurns = recent.size();
            windowChars = charsOf(recent);
            // 2026-09-18：这里原来有一道"前置过滤"（硬编码正则判"事务型窗口"就整窗跳过）。
            // 用户明确要求删掉：**该不该记全交给模型判断**，不要用正则替它做决定。
            // 它已经咬过一次——"教室/课表/第.周"整类被它当事务型跳过，正好把要记的事实挡在门外。
            // 代价是"整窗都在问课表"这种轮次也会真跑一次模型（一次约 0.05 元）；嫌贵就调大 ROUNDS，而不是加回正则。
            List<Memory> existing = memoryService.listActive(userId, Memory.KIND_TASK);
            // 无条件注入的那些（原 core）：活跃 + 只认显式来源
            List<Memory> cores = memoryService.listAlwaysInject(userId);
            // 已有事实卡片：事实不多时**直接列进主提示词**，让模型在输出 facts 时顺带给合并结论
            // （NEW/SUPERSEDES/SUPPLEMENT/SAME）→ 一次调用定稿，省掉"每条事实一次小调用"。
            // 卡片太多就不列（列不下就会被迫截断、可能漏掉该合并的那条），那时退回"向量召回 + 小判定"。
            List<ContextTurn> promptRecent = recent;
            int backgroundTurns = backgroundUserTurns(recent, burstTexts == null ? 0 : burstTexts.size());
            String factCards = factCardsForPrompt(userId);
            ExtractionResult result = parse(LlmScenario.run(LlmScenario.EXTRACT,
                    () -> chatModel.chat(buildPrompt(promptRecent, existing, cores, backgroundTurns, factCards))));
            if (result == null) {
                // 解析不出来**不能算"跑完了"**：那会把这段对话当成已处理、计数清零，它就再也不会被提取。
                // 返回 false 走失败重试，并把原因记进审计（面板能看到）。
                log.warn("记忆提取结果解析失败（本次不算已处理）user={} 窗口={} 轮", userId, recent.size());
                trail[1] = "PARSE_FAILED";
                return false;
            }
            if (factCards.isEmpty()) {
                // 没带卡片清单时模型看不到已有事实，它给的 relation/targetSubject 不能信 → 清掉，走召回+判定
                result = withoutFactDecisions(result);
            }
            trail[0] = verdictOf(result);
            log.info("记忆提取完成 user={} 窗口={}/{} 轮（背景 {}） facts={} episodes={} work={} core={} coreUpdates={} workUpdates={} completed={} duplicates={}",
                    userId, recent.size(), windowLimit(burstTexts), backgroundTurns, result.facts().size(),
                    result.episodes().size(), result.newWork().size(), result.coreCandidates().size(),
                    result.coreUpdates().size(), result.conflicts().size(), result.completedWork().size(),
                    result.duplicates().size());
            if (!currentCheck.getAsBoolean()) {
                log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                trail[1] = "STALE";
                return true;
            }
            List<ContextTurn> extractionRecent = recent;
            ExtractionResult writeBack = result;
            mutationLock.runExclusive(userId, () -> {
                if (!currentCheck.getAsBoolean()) {
                    log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                    trail[1] = "STALE";
                    return;
                }
                trail[2] = apply(userId, writeBack, extractionRecent);
            });
            return true;
        } catch (Exception e) {
            log.warn("记忆提取失败 user={}", userId, e);
            trail[1] = "FAILED";
            return false;
        } finally {
            if (audit != null) {
                audit.finish(userId, trigger == null ? MemoryExtractionRun.TRIGGER_AUTO : trigger, span, windowTurns, windowChars,
                        trail[0], trail[2], trail[1]);
            }
            MDC.remove("userScope");
        }
    }

    /** 审计用：这一趟模型判了多少条 */
    private String verdictOf(ExtractionResult result) {
        if (result == null) {
            return null;
        }
        return "core=" + result.coreCandidates().size() + " work=" + result.newWork().size()
                + " episode=" + result.episodes().size() + " updates=" + result.coreUpdates().size()
                + " completed=" + result.completedWork().size() + " duplicates=" + result.duplicates().size()
                + " facts=" + result.facts().size();
    }

    private int charsOf(List<ContextTurn> turns) {
        int chars = 0;
        for (ContextTurn turn : turns) {
            if (turn != null && turn.text() != null) {
                chars += turn.text().length();
            }
        }
        return chars;
    }

    public record NewWork(String content, int priority, String validUntil, List<String> sourceMessageIds,
                          int importance, int confidence, List<String> keywords) {
        public NewWork(String content, int priority) {
            this(content, priority, "", List.of(), DEFAULT_PRIORITY,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public NewWork(String content, int priority, String validUntil, List<String> sourceMessageIds) {
            this(content, priority, validUntil, sourceMessageIds, DEFAULT_PRIORITY,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public NewWork {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record CoreCandidate(String content, List<String> sourceMessageIds,
                                int importance, int confidence, List<String> keywords) {
        public CoreCandidate(String content) {
            this(content, List.of(), 5, MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public CoreCandidate(String content, List<String> sourceMessageIds) {
            this(content, sourceMessageIds, 5, MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public CoreCandidate {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record CoreUpdate(String oldContent, String newContent, List<String> sourceMessageIds,
                             int importance, int confidence, List<String> keywords) {
        public CoreUpdate(String oldContent, String newContent) {
            this(oldContent, newContent, List.of(), 5,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public CoreUpdate(String oldContent, String newContent, List<String> sourceMessageIds) {
            this(oldContent, newContent, sourceMessageIds, 5,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public CoreUpdate {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record WorkConflict(Long existingId, String existingContent, String proposedContent, String validUntil,
                               List<String> sourceMessageIds, int importance, int confidence, List<String> keywords) {
        public WorkConflict(Long existingId, String existingContent, String proposedContent) {
            this(existingId, existingContent, proposedContent, "", List.of(), DEFAULT_PRIORITY,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public WorkConflict(Long existingId, String existingContent, String proposedContent, String validUntil,
                            List<String> sourceMessageIds) {
            this(existingId, existingContent, proposedContent, validUntil, sourceMessageIds, DEFAULT_PRIORITY,
                    MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE, List.of());
        }

        public WorkConflict {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record WorkCompletion(Long existingId, String reason, List<String> sourceMessageIds, int confidence) {
        public WorkCompletion(Long existingId, String reason, List<String> sourceMessageIds) {
            this(existingId, reason, sourceMessageIds, MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE);
        }

        public WorkCompletion {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
            confidence = boundedConfidence(confidence);
        }

        private static int boundedConfidence(int value) {
            return Math.max(0, Math.min(100, value));
        }
    }

    public record EpisodeCandidate(String title, String summary, String episodeType, String occurredAt,
                                   int importance, int confidence, List<String> keywords,
                                   List<String> sourceMessageIds) {
        public EpisodeCandidate {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record ExtractionResult(List<EpisodeCandidate> episodes, List<NewWork> newWork,
                                   List<CoreCandidate> coreCandidates,
                                   List<CoreUpdate> coreUpdates, List<WorkConflict> conflicts,
                                   List<WorkCompletion> completedWork, List<String> duplicates,
                                   List<MemoryFactCandidate> facts) {
    }

    /**
     * 把这次提取的结论写回。
     *
     * @return **实际落库清单**（按操作计数），形如 {@code 新增核心记忆=1,新增情景记忆=1,事实条数=4}。
     *         它要和 verdict 里的"模型提议了多少条"放在一起看，才能回答
     *         "提议了 5 条 episode 为什么只落了 1 条"——这是 2026-10-09 之前**完全看不见**的那一面
     *         （审计里的 written_ids 从上线起一直硬编码传 null）。
     */
    private String apply(String userId, ExtractionResult result, List<ContextTurn> recent) {
        if (result == null) {
            return "";
        }
        writeTally.set(new LinkedHashMap<>());
        try {
            Set<String> allowedSourceIds = allowedSourceIds(recent);
            applyEpisodes(userId, result, allowedSourceIds);
            applyNewWork(userId, result, allowedSourceIds);
            applyCoreCandidates(userId, result, allowedSourceIds);
            applyCoreUpdates(userId, result, allowedSourceIds);
            applyWorkConflicts(userId, result, allowedSourceIds);
            applyWorkCompletions(userId, result, allowedSourceIds);
            applyFacts(userId, result);
            runSafely(userId, "过期工作记忆", memoryService::expireDueMemories);
            return writeSummary();
        } finally {
            writeTally.remove();
        }
    }

    /**
     * 本次提取的落库计数。
     *
     * <p>为什么用 ThreadLocal 而不是往 6 个 applyXxx 里传参：{@link #runSafely} 是**所有写入唯一的收口点**，
     * 在那里记一笔就全覆盖了；改成传参要动 6 个方法签名 + 14 个调用点，纯属白改。
     * {@link #apply} 是同步执行、且 finally 里 {@code remove()}，用法与 {@code LlmScenario.CURRENT} 一致。
     */
    private final ThreadLocal<Map<String, Integer>> writeTally = new ThreadLocal<>();

    private void tally(String operation, int count) {
        Map<String, Integer> counts = writeTally.get();
        if (counts == null || count <= 0) {
            return;
        }
        counts.merge(operation, count, Integer::sum);
    }

    /** 把落库计数拼成一行；空串 = 这次一条都没写成 */
    private String writeSummary() {
        Map<String, Integer> counts = writeTally.get();
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        counts.forEach((operation, count) -> {
            if (summary.length() > 0) {
                summary.append(',');
            }
            summary.append(operation).append('=').append(count);
        });
        return summary.toString();
    }

    /**
     * 事实层（v2，2026-09-18）：一条一句话，重建/取代交给 {@link MemoryFactService}（向量召回 + 模型判关系）。
     *
     * <p>这里**故意不做** {@link #isAcceptable} 那道长度门槛：事实的 object 天生很短（"303"、"周三"），
     * 用 core/work 的门槛会把它们全滤掉。只按 confidence 与列宽把关。
     */
    private void applyFacts(String userId, ExtractionResult result) {
        if (factService == null) {
            return;
        }
        List<MemoryFactCandidate> candidates = new ArrayList<>();
        for (MemoryFactCandidate candidate : safeList(result.facts())) {
            if (candidate == null || !candidate.usable()) {
                continue;
            }
            if (candidate.confidence() < minConfidence) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return;
        }
        runSafely(userId, "事实层写入", () -> {
            int written = factService.apply(userId, candidates);
            // 事实层能拿到**条数**（runSafely 只知道"调了一次"），所以额外记一行精确的：
            // 审计里同时有"调了几次"和"真正落了几条"，才能看出 candidates 有多少被去重挡掉
            tally("事实条数", written);
            log.info("事实层写入 user={} candidates={} new={}", userId, candidates.size(), written);
        });
    }

    private void applyEpisodes(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        if (memoryService == null) {
            return;
        }
        for (EpisodeCandidate candidate : safeList(result.episodes())) {
            if (candidate == null || !isAcceptable(candidate.summary(), candidate.confidence())) {
                continue;
            }
            MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                    candidate.confidence());
            runSafely(userId, "新增情景记忆", () -> memoryService.addExperience(userId, candidate.title(),
                    candidate.summary(), candidate.episodeType(), candidate.importance(), candidate.confidence(),
                    candidate.keywords(), parseOccurredAt(candidate.occurredAt()), provenance));
        }
    }

    // Applies newly extracted work memories after validation and deduplication.
    private void applyNewWork(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (NewWork candidate : safeList(result.newWork())) {
            if (candidate == null) {
                continue;
            }
            if (!isAcceptable(candidate.content(), candidate.confidence())
                    || isDuplicate(candidate.content(), result.duplicates())) {
                continue;
            }
            MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                    candidate.confidence());
            MemoryAttributes attributes = new MemoryAttributes(candidate.importance(), candidate.confidence(),
                    candidate.keywords());
            if (isDefaultAttributes(attributes, MemoryAttributes.defaults())) {
                runSafely(userId, "新增工作记忆", () -> memoryService.addTask(userId, candidate.content(),
                        candidate.priority(), "extraction", "AUTO", provenance,
                        parseValidUntil(candidate.validUntil())));
            } else {
                runSafely(userId, "新增工作记忆", () -> memoryService.addTask(userId, candidate.content(),
                        candidate.priority(), "extraction", "AUTO", provenance,
                        parseValidUntil(candidate.validUntil()), attributes));
            }
        }
    }

    // Applies only core candidates backed by a user message when source IDs are available.
    private void applyCoreCandidates(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (CoreCandidate candidate : safeList(result.coreCandidates())) {
            if (candidate == null) {
                continue;
            }
            if (!isAcceptable(candidate.content(), candidate.confidence())) {
                continue;
            }
            if (!allowedSourceIds.isEmpty() && (candidate.sourceMessageIds() == null || candidate.sourceMessageIds().stream()
                    .noneMatch(allowedSourceIds::contains))) {
                log.debug("跳过无用户原始消息依据的核心记忆 user={}", userId);
                continue;
            }
            MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                    candidate.confidence());
            MemoryAttributes attributes = new MemoryAttributes(candidate.importance(), candidate.confidence(),
                    candidate.keywords());
            // 【2026-09-14】确定性兜底：这条"新"核心事实其实和已有的某条是同一个主题（换了分数/日期/院校/称呼…）时，
            // 走替换而不是新增——模型偶尔不把它写成 coreUpdates，就会留下"旧事实还在"的观感（用户明确报过这个症状）。
            Memory similar = mostSimilarCore(userId, candidate.content());
            if (similar == null) {
                // 字面不像也可能是"同一件事换了个说法"（实测：「考研数学目标分是130」→ 模型抽成
                // 「用户在考研中设定的数学目标分数为140分，希望冲刺更高分数」，二元组重合度很低）。
                // 所以再问模型一次**只做判断**的小调用（Mem0 的两阶段做法）：这条新事实是不是某条已有记忆的新版本？
                similar = reconcileCoreWithModel(userId, candidate.content());
            }
            if (similar != null) {
                Long replacedId = similar.getId();
                log.info("核心记忆按新陈述替换旧条目 user={} old=\"{}\" new=\"{}\" similarity={}",
                        userId, similar.getContent(), candidate.content(),
                        String.format(java.util.Locale.ROOT, "%.2f",
                                MemoryTextSimilarity.similarity(similar.getContent(), candidate.content())));
                runSafely(userId, "替换核心记忆", () -> memoryService.replaceProfile(userId, replacedId,
                        candidate.content(), "与已有同类记忆冲突或重复，按用户最新陈述替换", "AUTO", provenance, attributes));
                continue;
            }
            if (isDefaultAttributes(attributes, MemoryAttributes.coreDefaults())) {
                runSafely(userId, "新增核心记忆", () -> memoryService.addProfile(userId, candidate.content(), "AUTO", provenance));
            } else {
                runSafely(userId, "新增核心记忆", () -> memoryService.addProfile(userId, candidate.content(), "AUTO", provenance,
                        attributes));
            }
        }
    }

    // Applies validated updates to existing core memories.
    private void applyCoreUpdates(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (CoreUpdate candidate : safeList(result.coreUpdates())) {
            if (candidate == null) {
                continue;
            }
            if (!isAcceptable(candidate.newContent(), candidate.confidence())
                    || candidate.oldContent() == null || candidate.oldContent().isBlank()) {
                continue;
            }
            if (!allowedSourceIds.isEmpty() && (candidate.sourceMessageIds() == null || candidate.sourceMessageIds().stream()
                    .noneMatch(allowedSourceIds::contains))) {
                continue;
            }
            runSafely(userId, "更新核心记忆", () -> memoryService.listAlwaysInject(userId).stream()
                    .filter(memory -> memory != null && memory.getContent() != null
                            && (memory.getContent().contains(candidate.oldContent())
                            || candidate.oldContent().contains(memory.getContent())))
                    .findFirst()
                    .ifPresent(memory -> {
                        MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                                candidate.confidence());
                        MemoryAttributes attributes = new MemoryAttributes(candidate.importance(), candidate.confidence(),
                                candidate.keywords());
                        if (isDefaultAttributes(attributes, MemoryAttributes.coreDefaults())) {
                            // 传 null 让服务自己套 coreDefaults()——与原 CoreMemoryService.update(…, provenance) 一致
                            memoryService.replaceProfile(userId, memory.getId(), candidate.newContent(),
                                    "用户明确陈述的新长期事实替代旧事实", "AUTO", provenance, null);
                        } else {
                            memoryService.replaceProfile(userId, memory.getId(), candidate.newContent(),
                                    "用户明确陈述的新长期事实替代旧事实", "AUTO", provenance, attributes);
                        }
                    }));
        }
    }

    // Applies conflict replacements to active work memories.
    private void applyWorkConflicts(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (WorkConflict candidate : safeList(result.conflicts())) {
            if (candidate == null) {
                continue;
            }
            if (candidate.existingId() == null || !isAcceptable(candidate.proposedContent(), candidate.confidence())) {
                continue;
            }
            MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                    candidate.confidence());
            MemoryAttributes attributes = new MemoryAttributes(candidate.importance(), candidate.confidence(),
                    candidate.keywords());
            if (isDefaultAttributes(attributes, MemoryAttributes.defaults())) {
                runSafely(userId, "替代工作记忆", () -> memoryService.replaceTask(userId,
                        candidate.existingId(), candidate.proposedContent(), provenance,
                        parseValidUntil(candidate.validUntil()), null));
            } else {
                runSafely(userId, "替代工作记忆", () -> memoryService.replaceTask(userId,
                        candidate.existingId(), candidate.proposedContent(), provenance,
                        parseValidUntil(candidate.validUntil()), attributes));
            }
        }
    }

    // Marks completed work memories and then lets lifecycle services expire due entries.
    private void applyWorkCompletions(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (WorkCompletion completion : safeList(result.completedWork())) {
            if (completion == null) {
                continue;
            }
            if (completion.existingId() == null || completion.confidence() < minConfidence) {
                continue;
            }
            runSafely(userId, "完成工作记忆", () -> memoryService.markCompleted(userId, completion.existingId(),
                    completion.reason(), provenance(userId, completion.sourceMessageIds(), allowedSourceIds,
                    completion.confidence())));
        }
    }

    private boolean isAcceptable(String content, int confidence) {
        return content != null && !content.isBlank() && content.trim().length() <= maxContentChars
                && confidence >= minConfidence;
    }

    private MemoryProvenance provenance(String userId, List<String> requestedSourceIds, Set<String> allowedSourceIds,
                                        int confidence) {
        List<String> messageIds = requestedSourceIds == null ? List.of() : requestedSourceIds.stream()
                .filter(allowedSourceIds::contains)
                .distinct()
                .toList();
        List<Long> mediaIds = new ArrayList<>();
        if (storedMediaRepository != null && !messageIds.isEmpty()) {
            try {
                    List<StoredMedia> matched = storedMediaRepository.findByUserIdAndSourceMessageIdInAndStatus(
                            userId, messageIds, StoredMedia.ACTIVE);
                    mediaIds = matched == null ? List.of() : matched.stream()
                            .filter(media -> media != null && userId.equals(media.getUserId()))
                            .map(StoredMedia::getId).filter(id -> id != null).distinct().toList();
            } catch (Exception exception) {
                log.debug("无法解析记忆关联资料 user={}: {}", userId, exception.getMessage());
            }
        }
        return new MemoryProvenance("USER_DERIVED", confidence, messageIds, mediaIds);
    }

    private boolean isDefaultAttributes(MemoryAttributes actual, MemoryAttributes defaults) {
        return actual.importance() == defaults.importance()
                && actual.confidence() == defaults.confidence()
                && actual.keywords().isEmpty();
    }

    private Set<String> allowedSourceIds(List<ContextTurn> recent) {
        Set<String> result = new LinkedHashSet<>();
        if (recent == null) {
            return result;
        }
        for (ContextTurn turn : recent) {
            if ("user".equals(turn.role()) && turn.sourceMessageIds() != null) {
                result.addAll(turn.sourceMessageIds());
            }
        }
        return result;
    }

    private LocalDateTime parseValidUntil(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDateTime parseOccurredAt(String value) {
        LocalDateTime parsed = parseValidUntil(value);
        return parsed == null ? LocalDateTime.now(zone) : parsed;
    }

    /** 已有核心记忆里跟这条新事实最像的一条（相似度不够就返回 null） */
    private Memory mostSimilarCore(String userId, String content) {
        if (content == null || content.isBlank() || memoryService == null) {
            return null;
        }
        Memory best = null;
        double bestScore = CORE_CONFLICT_SIMILARITY;
        for (Memory memory : memoryService.listAlwaysInject(userId)) {
            if (memory == null || memory.getContent() == null) {
                continue;
            }
            double score = MemoryTextSimilarity.similarity(memory.getContent(), content);
            if (score >= bestScore) {
                bestScore = score;
                best = memory;
            }
        }
        return best;
    }

    /**
     * 第二次调用（只做判断，不做抽取）：这条新事实是不是某条已有核心记忆的"新版本"？
     *
     * <p>为什么要单独问一次：换了个说法之后字面相似度会掉到很低（实测 0.3 左右），而**抽取和比对挤在一次调用里**
     * 时模型经常直接把新版本当成新事实输出（Mem0 也是把这两件事拆成两次调用做的：先抽事实，再拿候选与已有记忆
     * 逐条比对决定 ADD/UPDATE/DELETE/NOOP）。这里是最小版本：一次小调用，只回答"跟哪条是同一件事"。
     *
     * @return 命中的那条已有记忆；答"不是同一件事"或解析失败都返回 null（=新增，宁可不替换）
     */
    private Memory reconcileCoreWithModel(String userId, String content) {
        if (chatModel == null || memoryService == null || content == null || content.isBlank()) {
            return null;
        }
        List<Memory> cores = memoryService.listAlwaysInject(userId);
        if (cores.isEmpty() || cores.size() > RECONCILE_MAX_CORES) {
            return null;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("下面是一个用户的已有长期记忆（编号: 内容）：\n");
        for (int i = 0; i < cores.size(); i++) {
            prompt.append(i + 1).append(": ").append(cores.get(i).getContent()).append('\n');
        }
        prompt.append("\n刚从对话里抽到一条新事实：「").append(content).append("」\n\n");
        prompt.append("问：这条新事实是不是上面某一条的**新版本**（同一件事，只是数值/日期/状态/说法变了）？\n")
                .append("- 是 → 输出 {\"targetId\": 编号}\n")
                .append("- 不是（是个新主题；或者只是已有事实的补充细节、两者可以同时成立）→ 输出 {\"targetId\": null}\n")
                .append("拿不准就输出 null。只输出 JSON，不要解释。");
        try {
            String response = LlmScenario.run(LlmScenario.EXTRACT, () -> chatModel.chat(prompt.toString()));
            if (response == null || response.isBlank()) {
                return null;
            }
            String text = response.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            JsonNode target = node.path("targetId");
            if (!target.isInt() && !target.isLong()) {
                return null;
            }
            int index = target.asInt();
            if (index < 1 || index > cores.size()) {
                return null;
            }
            Memory hit = cores.get(index - 1);
            log.info("核心记忆比对（二次调用）user={} 判定为已有记忆的新版本 id={} old=\"{}\" new=\"{}\"",
                    userId, hit.getId(), hit.getContent(), content);
            return hit;
        } catch (Exception e) {
            // 判断失败就当"不是同一件事"，宁可不替换
            log.warn("核心记忆比对失败，按新增处理 user={}", userId, e);
            return null;
        }
    }

    private boolean isDuplicate(String content, List<String> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return false;
        }
        for (String duplicate : duplicates) {
            if (duplicate != null && !duplicate.isBlank()
                    && (content.contains(duplicate) || duplicate.contains(content))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把"当前有效的会变信息"渲染成卡片清单塞给模型（事实不多时）。
     *
     * <p>为什么要塞：模型要在**同一次调用**里判断"这条新事实跟已有的哪张卡是同一件事"。
     * 不塞的话只能先让它吐事实、再算向量召回、再叫一次模型判关系（每条事实一次小调用，实测 4 条多花 0.015 元 + 15 秒）。
     *
     * <p>事实太多（超过 {@code memory.fact-prompt-cards}，默认 30 张）或渲染太长
     * （超过 {@code memory.fact-prompt-cards-max-chars}，默认 4000 字）就返回空串 = 不带清单，
     * 写回时自动退回"召回 + 小判定"那条路——硬塞进去必然要截断，截断就可能漏掉该合并的那条。
     */
    private String factCardsForPrompt(String userId) {
        if (factService == null || userId == null || userId.isBlank() || factPromptCards <= 0) {
            return "";
        }
        try {
            List<MemoryFactService.FactCard> cards = factService.cards(userId, factPromptCards);
            if (cards.isEmpty()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (MemoryFactService.FactCard card : cards) {
                text.append("· ").append(card.subject()).append('：').append(card.valuesText()).append('\n');
            }
            if (text.length() > factPromptCardsMaxChars) {
                log.info("已有事实卡片太长（{} 字），本次不带进提示词、退回召回+判定 user={}", text.length(), userId);
                return "";
            }
            return text.toString();
        } catch (Exception e) {
            log.warn("读取事实卡片失败（本次不带进提示词）: {}", e.getMessage());
            return "";
        }
    }

    /** 不带卡片清单时，把模型给的 relation/targetSubject 清掉（它看不到已有事实，那些字段不可信） */
    private ExtractionResult withoutFactDecisions(ExtractionResult result) {
        if (result == null || result.facts().isEmpty()) {
            return result;
        }
        List<MemoryFactCandidate> cleaned = new ArrayList<>();
        for (MemoryFactCandidate fact : result.facts()) {
            cleaned.add(fact == null ? null : new MemoryFactCandidate(fact.subject(), fact.predicate(),
                    fact.object(), fact.content(), fact.source(), fact.confidence(), fact.docMediaId(),
                    fact.keywords(), fact.sourceMessageIds()));
        }
        return new ExtractionResult(result.episodes(), result.newWork(), result.coreCandidates(),
                result.coreUpdates(), result.conflicts(), result.completedWork(), result.duplicates(), cleaned);
    }

    private String buildPrompt(List<ContextTurn> recent, List<Memory> existing, List<Memory> cores,
                               int backgroundTurns, String factCards) {
        StringBuilder prompt = new StringBuilder();
        // 【2026-09-29 缓存修复】**不要把「当前时间」写在第一句**：上游的上下文缓存只认前缀，
        // 时间每轮都变 → 从第 10 个 token 起就全部作废，实测 prompt 7015 命中 0%（单次 0.026 元，
        // 是当时最大的单项开销）。现在时间挪到规则之后（见下面 "当前时间：" 那一行），
        // 于是「你是…提取器 + 全部规则」这一整段静态前缀能命中（命中价是未命中的 1/50）。
        // 以后往这个提示词里加东西，也一样：**静态的往前放，每轮会变的往后放**。
        prompt.append("你是用户的长期记忆提取器。只从最近对话中提取值得长期保留的信息。\n\n");
        prompt.append("规则：\n")
                .append("1. 只能依据 user 角色的明确陈述；assistant 回复、网页、工具结果和推测都不能成为记忆。**唯一例外是 facts**：用户自己发来的图片/文件里你确实看到的内容，可以写进 facts（source=DOC），但不要由附件正文推演出别的记忆。\n")
                .append("2. 稳定身份、长期目标、长期偏好、原则和底线写入 coreCandidates。核心记忆不设置有效期；只要用户明确表达且未来仍有价值，就应长期保留。\n")
                .append("3. 对未来交流有价值的完整经历写入 episodes：包含发生了什么、用户当时的处境/感受、结果或未决状态；一次性普通问答不要写。occurredAt 使用 yyyy-MM-ddTHH:mm:ss，episodeType 可用 EXPERIENCE、MILESTONE、RELATIONSHIP、DECISION 或 DOCUMENT。\n")
                .append("4. 持续项目、明确尚未结束的任务写入 newWorkItems。用户给出具体日期/时间，或明确相对期限（如明天、下周、本月）时，按当前时间换算 validUntil，使用 yyyy-MM-ddTHH:mm:ss；没有明确期限就留空。\n")
                .append("5. 用户明确说一个已有任务完成、取消或不再需要时，写入 completedWorkItems，existingId 必须来自已有中期记忆。不得因为你猜测或日期临近而标记完成。\n")
                .append("6. 用户明确修正已有事实时，分别使用 coreUpdates 或 workConflicts；系统会留痕，不能新增互相矛盾的旧事实。**如果新事实与「已存在的核心记忆」讲的是同一件事（改分数、改院校、改日期、改称呼、改偏好），必须写 coreUpdates 并把 oldContent 抄成那条已有记忆的原文，绝对不要新增一条平行的 coreCandidates**——否则旧事实会和新事实一起留在库里。\n")
                .append("7. 每个候选都要给 importance（1-5）和 confidence（0-100）。只有用户明确表达、未来仍有价值且 confidence 至少 ")
                .append(minConfidence).append(" 的内容才保留；拿不准时返回空数组。\n")
                .append("8. keywords 填 1-").append(maxKeywords)
                .append(" 个便于以后用不同说法找回的短词或短语，不要写推测。\n")
                .append("9. 闲聊、一次性问答和孤立情绪感叹不得新建事实记忆；已完成但具有人生连续性价值的事情可以只写入 episodes。\n")
                .append("10. 对每个新建、更新或完成项，sourceMessageIds 只能从该 user 消息行中方括号给出的 ID 选择。不得编造 ID；无可用 ID 时返回空数组。\n")
                .append("11. 与已有记忆语义重复度超过 ")
                .append(Math.round(dedupThreshold * 100)).append("% 时不新增，把原文放入 duplicates；重复的核心目标仍可放入 coreCandidates 以更新最后确认时间。\n")
                .append("12. 【会变的信息只写 facts】课表、教室/上课地点、第几节课的时间、临时日程、一次性的具体数字（如今天学了几小时），"
                        + "**一律不要写进 coreCandidates、newWorkItems、episodes**，而是写进 facts（一条一句话）。"
                        + "它们每周都在变，但事实层会按「同一件事的同一个属性」自动用新值取代旧值，所以**写进来是安全的、也是必须的**"
                        + "——不写就永远想不起来。每件事每次只写**当前有效**的那个值，不要写「以前是…后来改成…」。\n")
                .append("13. 【facts 的写法】subject=这件事的名字（同一件事每次必须用**完全一样**的 subject，例：第一周·周二晚·数学课）；"
                        + "predicate=属性（教室 / 时间 / 教师 / 周次）；object=值；content=一句完整事实；"
                        + "**object 必须是「能被另一个值替换掉」的具体值**（303 / 周二 19:00 / 王老师）；"
                        + "「有课」「体育课」这种只是状态、没有信息量、也替换不了的，不要单独记一条；"
                        + "source=USER 表示用户自己说的，source=DOC 表示你是从用户发来的图片或文件里看到的（只看你确实看到的，图里没有的不要补）。"
                        + "课表图这类信息请**逐条拆开**写，不要写成一句话塞很多件事。\n")
                .append("14. 【背景消息】标了 [背景/上次已处理] 的消息上一次提取时已经看过：**只用来理解上下文**"
                        + "（比如「那改成 305 吧」到底在改哪件事），不要只凭它们新建记忆；"
                        + "只有当这次的新消息明确修正或延续了它们时，才按新消息的内容写。\n");
        if (factCards != null && !factCards.isEmpty()) {
            // 带了卡片清单才加这条规则：让模型**在同一次输出里**把合并结论给出来（省掉后续每条一次小调用）
            prompt.append("15. 【facts 的合并】下面是系统现在记着的「会变的信息」。写 facts 时，如果这条跟某张卡片讲的是"
                    + "**同一件事**，必须同时给出 relation 和 targetSubject：\n")
                    .append("    - NEW：这件事没记过 → relation=NEW，targetSubject 留空。\n")
                    .append("    - SUPERSEDES：同一件事的**同一个属性**、新值取代旧值（教室 303→305）→ targetSubject 抄那张卡片的名字。\n")
                    .append("    - SUPPLEMENT：同一件事的**另一个属性**或额外细节（已有教室，新的是教师）→ targetSubject 抄那张卡片的名字。\n")
                    .append("    - SAME：同一件事的**同一个值**（只是换了说法）→ targetSubject 抄那张卡片的名字，不要重复记一条。\n")
                    .append("    targetSubject 必须**逐字照抄**卡片里冒号前面的那个名字；**同一个属性时 predicate 也照抄卡片的写法**"
                            + "（卡片写「教师」就别写成「老师」——写成别的会被当成另一件事，卡片上就会出现两个矛盾值）。\n");
        }
        // 动态内容从这里开始——上面那一整段（提取器身份 + 全部规则）是静态的，能进缓存
        prompt.append("\n当前时间：")
                .append(LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("（规则里说的「今天/本周/相对期限」都按这个时间换算）\n");
        prompt.append("\n已存在的核心记忆：\n");
        if (cores.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (Memory memory : cores) {
                // 格式压紧（2026-09-18 瘦身）：29 条核心记忆原来每行都写「；importance=；keywords=」，
                // 光标签就占 700 多字符。改成「（重要度｜关键词）」，模型照样读得懂。
                prompt.append("- ").append(memory.getContent())
                        .append('（').append(memory.getImportance())
                        .append('｜').append(memory.getKeywords() == null ? "" : memory.getKeywords())
                        .append("）\n");
            }
        }
        prompt.append("\n已存在的中期记忆（id: 内容（重要度｜截止｜关键词））：\n");
        if (existing.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (Memory memory : existing) {
                prompt.append(memory.getId()).append(": ").append(memory.getContent())
                        .append('（').append(memory.getImportance())
                        .append('｜').append(memory.getValidUntil() == null ? "" : memory.getValidUntil())
                        .append('｜').append(memory.getKeywords() == null ? "" : memory.getKeywords())
                        .append("）\n");
            }
        }
        if (factCards != null && !factCards.isEmpty()) {
            prompt.append("\n已记着的会变信息（写 facts 时按第 15 条给 relation / targetSubject）：\n").append(factCards);
        }
        prompt.append("\n最近对话：\n");
        int seenUserTurns = 0;
        for (ContextTurn turn : recent) {
            if (!"user".equals(turn.role())) {
                continue;
            }
            // 最老的这几条上一次提取已经处理过，只作背景（见 overlapTurns / backgroundUserTurns 的说明）
            boolean background = seenUserTurns++ < backgroundTurns;
            prompt.append("user");
            if (background) {
                prompt.append("[背景/上次已处理]");
            }
            prompt.append(" [sourceMessageIds=")
                    .append(String.join(",", turn.sourceMessageIds()))
                    .append("]: <USER_CONTENT>").append(turn.text()).append("</USER_CONTENT>\n");
        }
        // JSON 样例：默认值只在规则里写一次（原来每个字段都重复一遍 importance/confidence，白占 200 字符）
        prompt.append("\n只输出 JSON，不要解释（importance 默认 ").append(defaultCoreImportance)
                .append("、confidence 默认 ").append(defaultConfidence).append("，可以不写）：\n")
                .append("{\"episodes\":[{\"title\":\"\",\"summary\":\"\",\"episodeType\":\"EXPERIENCE\",\"occurredAt\":\"\",\"importance\":0,\"confidence\":0,\"keywords\":[],\"sourceMessageIds\":[]}],")
                .append("\"newWorkItems\":[{\"content\":\"\",\"priority\":").append(defaultPriority)
                .append(",\"validUntil\":\"\"}],")
                .append("\"coreCandidates\":[{\"content\":\"\"}],")
                .append("\"coreUpdates\":[{\"oldContent\":\"\",\"newContent\":\"\"}],")
                .append("\"workConflicts\":[{\"existingId\":0,\"existingContent\":\"\",\"proposedContent\":\"\",\"validUntil\":\"\"}],")
                .append("\"completedWorkItems\":[{\"existingId\":0,\"reason\":\"\"}],")
                .append("\"facts\":[{\"subject\":\"第一周·周二晚·数学课\",\"predicate\":\"教室\",\"object\":\"303\",")
                .append("\"content\":\"第一周周二晚数学课的教室是303\",\"source\":\"USER\",\"relation\":\"NEW\",\"targetSubject\":\"\"}],")
                .append("\"duplicates\":[]}\n")
                .append("（上面每个对象都可以带 importance/confidence/keywords/sourceMessageIds，没写就按默认；空数组就写 []；"
                        + "**facts 不需要 keywords**——事实层靠向量召回和 subject 聚合，关键词没人读）");
        return prompt.toString();
    }

    private ExtractionResult parse(String response) {
        String text = response == null ? "{}" : response.trim();
        if (text.startsWith(FENCE)) {
            text = text.replaceAll(FENCE + "(json)?", "").trim();
            int end = text.lastIndexOf(FENCE);
            if (end >= 0) {
                text = text.substring(0, end).trim();
            }
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            return new ExtractionResult(parseEpisodes(root), parseNewWork(root), parseCoreCandidates(root), parseCoreUpdates(root),
                    parseWorkConflicts(root), parseCompletedWork(root), parseDuplicates(root), parseFacts(root));
        } catch (Exception exception) {
            log.warn("解析记忆提取结果失败", exception);
        }
        // null = 解析失败（**不是**"什么都没提取到"）：调用方要把它当失败，否则这段对话会被当成已处理
        return null;
    }

    private List<EpisodeCandidate> parseEpisodes(JsonNode root) {
        List<EpisodeCandidate> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("episodes")) {
            if (++count > maxCandidateItems) break;
            values.add(new EpisodeCandidate(node.path("title").asText(""), node.path("summary").asText(""),
                    node.path("episodeType").asText("EXPERIENCE"), node.path("occurredAt").asText(""),
                    boundedInt(node.path("importance").asInt(defaultWorkImportance), 1, 5),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100),
                    stringList(node.path("keywords")), sourceMessageIds(node.path("sourceMessageIds"))));
        }
        return values;
    }

    // Parses newly proposed work memories from the model response.
    private List<NewWork> parseNewWork(JsonNode root) {
        List<NewWork> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("newWorkItems")) {
            if (++count > maxCandidateItems) break;
            values.add(new NewWork(node.path("content").asText(""), node.path("priority").asInt(defaultPriority),
                    node.path("validUntil").asText(""), sourceMessageIds(node.path("sourceMessageIds")),
                    boundedInt(node.path("importance").asInt(defaultWorkImportance), 1, 5),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100),
                    stringList(node.path("keywords"))));
        }
        return values;
    }

    // Parses stable core-memory candidates from the model response.
    private List<CoreCandidate> parseCoreCandidates(JsonNode root) {
        List<CoreCandidate> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("coreCandidates")) {
            if (++count > maxCandidateItems) break;
            values.add(new CoreCandidate(node.path("content").asText(""), sourceMessageIds(node.path("sourceMessageIds")),
                    boundedInt(node.path("importance").asInt(defaultCoreImportance), 1, 5),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100),
                    stringList(node.path("keywords"))));
        }
        return values;
    }

    // Parses corrections to existing core memories from the model response.
    private List<CoreUpdate> parseCoreUpdates(JsonNode root) {
        List<CoreUpdate> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("coreUpdates")) {
            if (++count > maxCandidateItems) break;
            values.add(new CoreUpdate(node.path("oldContent").asText(""), node.path("newContent").asText(""),
                    sourceMessageIds(node.path("sourceMessageIds")),
                    boundedInt(node.path("importance").asInt(defaultCoreImportance), 1, 5),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100),
                    stringList(node.path("keywords"))));
        }
        return values;
    }

    // Parses conflicts against existing work memories from the model response.
    private List<WorkConflict> parseWorkConflicts(JsonNode root) {
        List<WorkConflict> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("workConflicts")) {
            if (++count > maxCandidateItems) break;
            values.add(new WorkConflict(node.path("existingId").isMissingNode() ? null : node.path("existingId").asLong(),
                    node.path("existingContent").asText(""), node.path("proposedContent").asText(""),
                    node.path("validUntil").asText(""), sourceMessageIds(node.path("sourceMessageIds")),
                    boundedInt(node.path("importance").asInt(defaultWorkImportance), 1, 5),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100),
                    stringList(node.path("keywords"))));
        }
        return values;
    }

    // Parses explicit completion events for existing work memories.
    private List<WorkCompletion> parseCompletedWork(JsonNode root) {
        List<WorkCompletion> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("completedWorkItems")) {
            if (++count > maxCandidateItems) break;
            values.add(new WorkCompletion(node.path("existingId").isMissingNode() ? null : node.path("existingId").asLong(),
                    node.path("reason").asText(""), sourceMessageIds(node.path("sourceMessageIds")),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100)));
        }
        return values;
    }

    // Parses duplicate-content hints used to avoid creating redundant memories.
    private List<String> parseDuplicates(JsonNode root) {
        List<String> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("duplicates")) {
            if (++count > maxCandidateItems) break;
            values.add(node.asText(""));
        }
        return values;
    }

    // Parses atomic facts (memory v2): one sentence per fact, merged by subject+predicate later.
    private List<MemoryFactCandidate> parseFacts(JsonNode root) {
        List<MemoryFactCandidate> values = new ArrayList<>();
        int count = 0;
        for (JsonNode node : root.path("facts")) {
            if (++count > maxCandidateItems) break;
            Long docMediaId = node.path("docMediaId").isNumber() ? node.path("docMediaId").asLong() : null;
            values.add(new MemoryFactCandidate(node.path("subject").asText(""), node.path("predicate").asText(""),
                    node.path("object").asText(""), node.path("content").asText(""),
                    node.path("source").asText("USER"),
                    boundedInt(node.path("confidence").asInt(defaultConfidence), 0, 100), docMediaId,
                    // facts 不要 keywords：事实层是向量召回 + subject 聚合，关键词写了也没人读（2026-09-18 去掉）
                    List.of(), sourceMessageIds(node.path("sourceMessageIds")),
                    node.path("relation").asText(null), node.path("targetSubject").asText(null)));
        }
        return values;
    }

    private List<String> stringList(JsonNode node) {
        return stringList(node, maxKeywords);
    }

    private List<String> sourceMessageIds(JsonNode node) {
        return stringList(node, maxCandidateItems);
    }

    private List<String> stringList(JsonNode node, int limit) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode value : node) {
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                result.add(text);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private int boundedInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void runSafely(String userId, String operation, Runnable action) {
        try {
            action.run();
            tally(operation, 1);
        } catch (Exception exception) {
            // 失败也记一笔：不然审计里"落库"少一条时，分不清是"没写"还是"写失败被吞了"
            tally("失败·" + operation, 1);
            log.warn("记忆提取单项操作失败 user={} operation={} reason={}", userId, operation,
                    exception.getClass().getSimpleName());
        }
    }
}
