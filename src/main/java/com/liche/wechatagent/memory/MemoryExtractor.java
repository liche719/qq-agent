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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final String FENCE = "\u0060\u0060\u0060";
    private static final int DEFAULT_PRIORITY = 3;

    /** 窗口内用户消息至少要有这么多"实字"才值得跑一次提取（见 worthExtracting） */
    private static final int MIN_SUBSTANCE_CHARS = 5;

    /**
     * 前置过滤的两组规则（2026-09-17 定稿，回放数据见 docs/memory-hybrid-plan.md §4.3.1）。
     *
     * <p>{@link #MUST_KEEP} **任一命中就一定要跑**——长期身份/偏好/目标/底线，以及"修正某条已有事实"的表达。
     * 其余五个是"事务型"小类：整窗新消息全部命中它们时跳过。
     */
    private static final Pattern MUST_KEEP = Pattern.compile(
            "记住|记一下|记着|别忘了|以后|我习惯|我喜欢|我不喜欢|我是|我的|我打算|想先|备考|专硕|学硕|考研"
                    + "|目标|计划|生日|电话|地址|过敏|室友|女朋友|男朋友|改成|纠正|说错|不是.{0,8}是"
                    + "|不要推送|别推送|不要做|底线|原则|保证|承诺");
    /** 课表/教室/时间这类**话题**词。
     *  **2026-09-18 起它单独不再等于"事务型"**：v2 事实层要记的就是这些值（教室/时间），
     *  只有"在问"（同时命中 {@link #ASKING}）才跳过，陈述句一律照跑——否则"第一周周二晚数学课在 303"
     *  这种正好要记的句子会被前置过滤挡掉，事实层永远是空的。
     *  **不要放裸「时间」**：`我想重新规划一下每天的时间` 会被误判成事务型（实测踩到）。 */
    private static final Pattern CLASSROOM_TOPIC = Pattern.compile(
            "教室|课表|什么课|有课|上课|第.周|几点上|几点下|几号|星期|周几|几点|哪个教室");
    /** 提问信号：和话题词同时出现才算"只是在问"（不产生新值） */
    private static final Pattern ASKING = Pattern.compile(
            "[?？]|吗|呢|在哪|哪个|几点|几号|是不是|有没有|还有|怎么走|什么时候"
                    + "|现在.{0,4}(时间|几点)|今天.{0,4}(几号|星期|周几)");
    /** 元问题：问它自己干了什么 */
    private static final Pattern TRANSACTIONAL_META = Pattern.compile(
            "刚刚.{0,6}(记录|工具|说)|什么工具|什么功能|你想做什么|自己的想法|记录了什么|留的线索");
    /** 提醒操作（提醒已经落 reminder_task 表，不需要再进记忆） */
    private static final Pattern TRANSACTIONAL_REMINDER = Pattern.compile("提醒|分钟后|小时后");
    /** 资料操作：看/发它存过的文件 */
    private static final Pattern TRANSACTIONAL_MEDIA = Pattern.compile("文件|资料|图片|截图|发我|保存好的");
    /** 纯噪声：只有标点、语气词、纯应答。**不要按长度一刀切**——
     * `考公`（2 字）、`不吃了`（3 字）这种短句可能正是要记的（实测踩到）。 */
    private static final Pattern TRANSACTIONAL_NOISE = Pattern.compile(
            "^[\\s?？。！!~～、,，.]+$"
                    + "|^(呜呜+|哭+|草|嗯+|哦+|啊+|哈+|呵+)$"
                    + "|^(好|行|好的|行吧|收到|谢谢|谢了|ok|OK|Ok|不客气|没事)$");

    /**
     * 新核心事实与已有核心记忆的相似度超过它 → 判定为"同一个事实的新版本"，走替换（旧条目 SUPERSEDED）而不是新增。
     * 0.72 是在「考研数学目标分是130 / 目标分改成140」（≈0.75）与「不喜欢咖啡 / 喜欢喝茶」（≈0.2）之间取的。
     */
    private static final double CORE_CONFLICT_SIMILARITY = 0.72d;

    /** 核心记忆超过这么多条时，就不做"这条是不是某条的新版本"的二次判断了（太多了模型也判不准） */
    private static final int RECONCILE_MAX_CORES = 60;

    private final ChatModel chatModel;
    private final ContextStore contextStore;
    private final WorkMemoryService workMemoryService;
    private final CoreMemoryService coreMemoryService;
    private final MemoryArchiveService archiveService;
    private final ObjectMapper objectMapper;
    private final int recentTurns;
    private final StoredMediaRepository storedMediaRepository;
    private final MemoryMutationLock mutationLock;
    private final ConversationMemoryService conversationMemoryService;
    private final EpisodicMemoryService episodicMemoryService;
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

    /** 前置过滤档位：{@code transactional-only}（默认，事务型窄跳过）/ {@code off}（退回旧门槛） */
    private String prefilter = "transactional-only";

    @Value("${memory.extraction-prefilter:transactional-only}")
    public void setPrefilter(String prefilter) {
        this.prefilter = prefilter == null || prefilter.isBlank() ? "transactional-only" : prefilter.trim();
    }

    /** 事实层（2026-09-18）：会变的信息（课表/教室/时间…）写这里，见 {@link MemoryFactService}。没装配就整块跳过 */
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

    /**
     * 这一趟要读多长的窗口（对话行数）：
     * 默认 {@code memory.extraction-recent-turns}（40 行 ≈ 20 条机主消息），
     * 但**新消息比它多时必须跟着放大**——轮次触发是 15 条，可要是用户一口气说了 25 条
     * （或者提取被最小间隔/合并窗口推迟了），40 行就装不下，最老的几条会落在窗口外、**静默漏记**。
     * 上限由 {@code memory.conversation-extraction-limit}（默认 80 行）兜住。
     */
    private int windowLimit(List<String> burstTexts) {
        int newTurns = burstTexts == null ? 0 : burstTexts.size();
        return Math.max(recentTurns, Math.min(400, newTurns * 2 + 10));
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
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           @Value("${memory.extraction-recent-turns:40}") int recentTurns,
                           StoredMediaRepository storedMediaRepository,
                           MemoryMutationLock mutationLock,
                           ConversationMemoryService conversationMemoryService,
                           EpisodicMemoryService episodicMemoryService,
                           @Value("${memory.min-confidence:60}") int minConfidence,
                           MemoryPolicyProperties policyProperties,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.chatModel = chatModel;
        this.contextStore = contextStore;
        this.workMemoryService = workMemoryService;
        this.coreMemoryService = coreMemoryService;
        this.archiveService = archiveService;
        this.objectMapper = objectMapper;
        this.storedMediaRepository = storedMediaRepository;
        this.mutationLock = mutationLock;
        this.conversationMemoryService = conversationMemoryService;
        this.episodicMemoryService = episodicMemoryService;
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

    MemoryExtractor(ChatModel chatModel,
                    ContextStore contextStore,
                    WorkMemoryService workMemoryService,
                    CoreMemoryService coreMemoryService,
                    MemoryArchiveService archiveService,
                    ObjectMapper objectMapper,
                    int recentTurns,
                    StoredMediaRepository storedMediaRepository,
                    MemoryMutationLock mutationLock,
                    ConversationMemoryService conversationMemoryService,
                    int minConfidence,
                    MemoryPolicyProperties policyProperties,
                    String timeZoneId) {
        this(chatModel, contextStore, workMemoryService, coreMemoryService, archiveService, objectMapper,
                recentTurns, storedMediaRepository, mutationLock, conversationMemoryService, null,
                minConfidence, policyProperties, timeZoneId);
    }

    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           int recentTurns,
                           StoredMediaRepository storedMediaRepository,
                           MemoryMutationLock mutationLock) {
        this(chatModel, contextStore, workMemoryService, coreMemoryService, archiveService, objectMapper,
                recentTurns, storedMediaRepository, mutationLock, null, null, 60,
                new MemoryPolicyProperties(), "Asia/Shanghai");
    }

    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           int recentTurns) {
        this(chatModel, contextStore, workMemoryService, coreMemoryService, archiveService, objectMapper,
                recentTurns, null, new MemoryMutationLock(), null, null, 60,
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

    /**
     * @param burstTexts 这一轮静默窗口里**用户说过的话**（判"事务型窗口"用，规则见
     *                   docs/memory-hybrid-plan.md §4.3.1）；空列表时退回"整窗判定"（保守：判不了就不挡）
     */
    public boolean extract(String userId, List<String> burstTexts, BooleanSupplier stillCurrent) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        BooleanSupplier currentCheck = stillCurrent == null ? () -> true : stillCurrent;
        MDC.put("userScope", UserScope.forUser(userId));
        MemoryExtractionAudit.Span span = audit == null ? null : audit.begin();
        // [0]=verdict（模型判定计数） [1]=reason（跳过原因）——用数组是为了能在下面的 lambda 里赋值
        String[] trail = new String[]{null, null};
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
            String skipped = prefilterSkip(recent, burstTexts);
            if (skipped != null) {
                log.info("记忆提取跳过：{} user={} turns={} burst={}", skipped, userId, recent.size(),
                        burstTexts == null ? 0 : burstTexts.size());
                trail[1] = skipped;
                return true;
            }
            List<UserWorkMemory> existing = workMemoryService.listActive(userId);
            List<UserCoreMemory> cores = coreMemoryService.listActive(userId);
            // 结构化抽取：温度 0（见 LlmScenarioSettings）；深度思考 2026-09-14 起不再关（记忆质量优先，且提取是后台异步跑）
            List<ContextTurn> promptRecent = recent;
            int backgroundTurns = backgroundUserTurns(recent, burstTexts == null ? 0 : burstTexts.size());
            ExtractionResult result = parse(LlmScenario.run(LlmScenario.EXTRACT,
                    () -> chatModel.chat(buildPrompt(promptRecent, existing, cores, backgroundTurns))));
            if (result == null) {
                // 解析不出来**不能算"跑完了"**：那会把这段对话当成已处理、计数清零，它就再也不会被提取。
                // 返回 false 走失败重试，并把原因记进审计（面板能看到）。
                log.warn("记忆提取结果解析失败（本次不算已处理）user={} 窗口={} 轮", userId, recent.size());
                trail[1] = "PARSE_FAILED";
                return false;
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
            mutationLock.runExclusive(userId, () -> {
                if (!currentCheck.getAsBoolean()) {
                    log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                    trail[1] = "STALE";
                    return;
                }
                apply(userId, result, extractionRecent);
            });
            return true;
        } catch (Exception e) {
            log.warn("记忆提取失败 user={}", userId, e);
            trail[1] = "FAILED";
            return false;
        } finally {
            if (audit != null) {
                audit.finish(userId, MemoryExtractionRun.TRIGGER_AUTO, span, windowTurns, windowChars,
                        trail[0], null, trail[1]);
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

    private void apply(String userId, ExtractionResult result, List<ContextTurn> recent) {
        if (result == null) {
            return;
        }
        Set<String> allowedSourceIds = allowedSourceIds(recent);
        applyEpisodes(userId, result, allowedSourceIds);
        applyNewWork(userId, result, allowedSourceIds);
        applyCoreCandidates(userId, result, allowedSourceIds);
        applyCoreUpdates(userId, result, allowedSourceIds);
        applyWorkConflicts(userId, result, allowedSourceIds);
        applyWorkCompletions(userId, result, allowedSourceIds);
        applyFacts(userId, result);
        runSafely(userId, "过期工作记忆", workMemoryService::expireDueMemories);
        runSafely(userId, "归档工作记忆", () -> archiveService.compressIfNeeded(userId));
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
            log.info("事实层写入 user={} candidates={} new={}", userId, candidates.size(), written);
        });
    }

    private void applyEpisodes(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        if (episodicMemoryService == null) {
            return;
        }
        for (EpisodeCandidate candidate : safeList(result.episodes())) {
            if (candidate == null || !isAcceptable(candidate.summary(), candidate.confidence())) {
                continue;
            }
            MemoryProvenance provenance = provenance(userId, candidate.sourceMessageIds(), allowedSourceIds,
                    candidate.confidence());
            runSafely(userId, "新增情景记忆", () -> episodicMemoryService.add(userId, candidate.title(),
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
                runSafely(userId, "新增工作记忆", () -> workMemoryService.add(userId, candidate.content(),
                        candidate.priority(), "extraction", "AUTO", provenance,
                        parseValidUntil(candidate.validUntil())));
            } else {
                runSafely(userId, "新增工作记忆", () -> workMemoryService.add(userId, candidate.content(),
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
            UserCoreMemory similar = mostSimilarCore(userId, candidate.content());
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
                runSafely(userId, "替换核心记忆", () -> coreMemoryService.replaceFromExtraction(userId, replacedId,
                        candidate.content(), "与已有同类记忆冲突或重复，按用户最新陈述替换", "AUTO", provenance, attributes));
                continue;
            }
            if (isDefaultAttributes(attributes, MemoryAttributes.coreDefaults())) {
                runSafely(userId, "新增核心记忆", () -> coreMemoryService.add(userId, candidate.content(), "AUTO", provenance));
            } else {
                runSafely(userId, "新增核心记忆", () -> coreMemoryService.add(userId, candidate.content(), "AUTO", provenance,
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
            runSafely(userId, "更新核心记忆", () -> coreMemoryService.listActive(userId).stream()
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
                            coreMemoryService.update(userId, memory.getId(), candidate.newContent(),
                                    "用户明确陈述的新长期事实替代旧事实", "AUTO", provenance);
                        } else {
                            coreMemoryService.update(userId, memory.getId(), candidate.newContent(),
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
                runSafely(userId, "替代工作记忆", () -> workMemoryService.updateFromExtraction(userId,
                        candidate.existingId(), candidate.proposedContent(), provenance,
                        parseValidUntil(candidate.validUntil())));
            } else {
                runSafely(userId, "替代工作记忆", () -> workMemoryService.updateFromExtraction(userId,
                        candidate.existingId(), candidate.proposedContent(), provenance,
                        parseValidUntil(candidate.validUntil()), attributes));
            }
        }
    }

    // Marks completed work memories and then lets lifecycle services expire and archive due entries.
    private void applyWorkCompletions(String userId, ExtractionResult result, Set<String> allowedSourceIds) {
        for (WorkCompletion completion : safeList(result.completedWork())) {
            if (completion == null) {
                continue;
            }
            if (completion.existingId() == null || completion.confidence() < minConfidence) {
                continue;
            }
            runSafely(userId, "完成工作记忆", () -> workMemoryService.markCompleted(userId, completion.existingId(),
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
    private UserCoreMemory mostSimilarCore(String userId, String content) {
        if (content == null || content.isBlank() || coreMemoryService == null) {
            return null;
        }
        UserCoreMemory best = null;
        double bestScore = CORE_CONFLICT_SIMILARITY;
        for (UserCoreMemory memory : coreMemoryService.listActive(userId)) {
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
    private UserCoreMemory reconcileCoreWithModel(String userId, String content) {
        if (chatModel == null || coreMemoryService == null || content == null || content.isBlank()) {
            return null;
        }
        List<UserCoreMemory> cores = coreMemoryService.listActive(userId);
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
            UserCoreMemory hit = cores.get(index - 1);
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
     * 提取前的**零成本前置过滤**（2026-09-17）。
     *
     * <p>默认 {@code transactional-only}：**不再按"长度/数字/我"判**（那道旧门槛会漏掉「考公」这种短而重要的句子），
     * 改成"默认就跑，只在**这一轮新消息全是事务型**时跳过"。事务型 = 问课表/教室/时间、问它存过的资料、
     * 元问题（"刚刚记录了什么"）、设/改提醒、纯噪声（≤4 字或语气词）。
     *
     * <p>规则表与回放数据见 docs/memory-hybrid-plan.md §4.3.1（最近 3 天 41 个窗口里可跳过 19 个，46%）。
     * **任一命中"长期信号"就跑**；判不了（没有新消息文本）也跑——宁可多花一分钱，不冒丢记忆的险。
     *
     * @return null = 正常跑；否则是要记进审计的跳过原因
     */
    private String prefilterSkip(List<ContextTurn> recent, List<String> burstTexts) {
        if ("off".equalsIgnoreCase(prefilter)) {
            // 回退开关：退回 2026-09-14 那道旧门槛
            return worthExtracting(recent) ? null : "PRECHECK";
        }
        if (burstTexts == null || burstTexts.isEmpty()) {
            return null;
        }
        for (String text : burstTexts) {
            if (text != null && MUST_KEEP.matcher(text).find()) {
                return null;
            }
        }
        for (String text : burstTexts) {
            if (!isTransactional(text)) {
                return null;
            }
        }
        return "TRANSACTIONAL";
    }

    /** 事务型消息：不值得为它花一次带思考的调用（判定宽松——拿不准就算"不是事务型"，照跑） */
    private boolean isTransactional(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String trimmed = text.trim();
        boolean classroomQuestion = CLASSROOM_TOPIC.matcher(trimmed).find() && ASKING.matcher(trimmed).find();
        return classroomQuestion
                || TRANSACTIONAL_META.matcher(trimmed).find()
                || TRANSACTIONAL_REMINDER.matcher(trimmed).find()
                || TRANSACTIONAL_MEDIA.matcher(trimmed).find()
                || TRANSACTIONAL_NOISE.matcher(trimmed).find();
    }

    /**
     * 窗口里有没有"值得记"的内容：只要有一条**用户消息**算实质内容就返回 true。
     *
     * <p>判定故意宽松（宁可多跑一次提取，也不要漏记）：去掉标点后 ≥ {@value #MIN_SUBSTANCE_CHARS} 个字、
     * 或含数字（日期/分数/时长往往就靠这个）、或含「我/咱」（第一人称陈述）。所以
     * 「好」「在吗」「嗯嗯」「谢谢」会被跳过，而「我不喝咖啡」「考试推迟了」不会。
     *
     * <p>**2026-09-17 起只在 {@code memory.extraction-prefilter=off} 时才用**（默认走事务型窄跳过）。
     */
    private boolean worthExtracting(List<ContextTurn> recent) {
        for (ContextTurn turn : recent) {
            if (turn == null || !"user".equals(turn.role()) || turn.text() == null) {
                continue;
            }
            String normalized = MemoryTextSimilarity.normalize(turn.text());
            if (normalized.length() >= MIN_SUBSTANCE_CHARS
                    || normalized.chars().anyMatch(Character::isDigit)
                    || normalized.indexOf('我') >= 0 || normalized.indexOf('咱') >= 0) {
                return true;
            }
        }
        return false;
    }

    private String buildPrompt(List<ContextTurn> recent, List<UserWorkMemory> existing, List<UserCoreMemory> cores,
                               int backgroundTurns) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是用户的长期记忆提取器。当前时间：")
                .append(LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("。只从最近对话中提取值得长期保留的信息。\n\n");
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
                        + "predicate=属性（教室 / 时间 / 教师 / 周次）；object=值（303 / 周二 19:00）；content=一句完整事实；"
                        + "source=USER 表示用户自己说的，source=DOC 表示你是从用户发来的图片或文件里看到的（只看你确实看到的，图里没有的不要补）。"
                        + "课表图这类信息请**逐条拆开**写，不要写成一句话塞很多件事。\n")
                .append("14. 【背景消息】标了 [背景/上次已处理] 的消息上一次提取时已经看过：**只用来理解上下文**"
                        + "（比如「那改成 305 吧」到底在改哪件事），不要只凭它们新建记忆；"
                        + "只有当这次的新消息明确修正或延续了它们时，才按新消息的内容写。\n\n");
        prompt.append("已存在的核心记忆：\n");
        if (cores.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (UserCoreMemory memory : cores) {
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
            for (UserWorkMemory memory : existing) {
                prompt.append(memory.getId()).append(": ").append(memory.getContent())
                        .append('（').append(memory.getImportance())
                        .append('｜').append(memory.getValidUntil() == null ? "" : memory.getValidUntil())
                        .append('｜').append(memory.getKeywords() == null ? "" : memory.getKeywords())
                        .append("）\n");
            }
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
                .append("\"content\":\"第一周周二晚数学课的教室是303\",\"source\":\"USER\"}],")
                .append("\"duplicates\":[]}\n")
                .append("（上面每个对象都可以带 importance/confidence/keywords/sourceMessageIds，没写就按默认；空数组就写 []）");
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
                    stringList(node.path("keywords"), maxKeywords), sourceMessageIds(node.path("sourceMessageIds"))));
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
        } catch (Exception exception) {
            log.warn("记忆提取单项操作失败 user={} operation={} reason={}", userId, operation,
                    exception.getClass().getSimpleName());
        }
    }
}
