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

@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final String FENCE = "\u0060\u0060\u0060";
    private static final int DEFAULT_PRIORITY = 3;

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

    @Autowired
    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           @Value("${memory.extraction-recent-turns:20}") int recentTurns,
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
        if (userId == null || userId.isBlank()) {
            return true;
        }
        BooleanSupplier currentCheck = stillCurrent == null ? () -> true : stillCurrent;
        MDC.put("userScope", UserScope.forUser(userId));
        try {
            if (!currentCheck.getAsBoolean()) {
                return true;
            }
            List<ContextTurn> recent = conversationMemoryService == null
                    ? List.of() : conversationMemoryService.recentForExtraction(userId, recentTurns);
            if (recent.isEmpty()) {
                recent = contextStore.getRecent(userId, recentTurns);
            }
            if (recent.isEmpty()) {
                return true;
            }
            List<UserWorkMemory> existing = workMemoryService.listActive(userId);
            List<UserCoreMemory> cores = coreMemoryService.listActive(userId);
            // 结构化抽取：温度 0（见 LlmScenarioSettings）；深度思考 2026-09-14 起不再关（记忆质量优先，且提取是后台异步跑）
            List<ContextTurn> promptRecent = recent;
            ExtractionResult result = parse(LlmScenario.run(LlmScenario.EXTRACT,
                    () -> chatModel.chat(buildPrompt(promptRecent, existing, cores))));
            log.info("记忆提取完成 user={} episodes={} work={} core={} coreUpdates={} workUpdates={} completed={} duplicates={}", userId,
                    result.episodes().size(), result.newWork().size(), result.coreCandidates().size(), result.coreUpdates().size(),
                    result.conflicts().size(), result.completedWork().size(), result.duplicates().size());
            if (!currentCheck.getAsBoolean()) {
                log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                return true;
            }
            List<ContextTurn> extractionRecent = recent;
            mutationLock.runExclusive(userId, () -> {
                if (!currentCheck.getAsBoolean()) {
                    log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                    return;
                }
                apply(userId, result, extractionRecent);
            });
            return true;
        } catch (Exception e) {
            log.warn("记忆提取失败 user={}", userId, e);
            return false;
        } finally {
            MDC.remove("userScope");
        }
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
                                   List<WorkCompletion> completedWork, List<String> duplicates) {
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
        runSafely(userId, "过期工作记忆", workMemoryService::expireDueMemories);
        runSafely(userId, "归档工作记忆", () -> archiveService.compressIfNeeded(userId));
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

    private String buildPrompt(List<ContextTurn> recent, List<UserWorkMemory> existing, List<UserCoreMemory> cores) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是用户的长期记忆提取器。当前时间：")
                .append(LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("。只从最近对话中提取值得长期保留的信息。\n\n");
        prompt.append("规则：\n")
                .append("1. 只能依据 user 角色的明确陈述；assistant 回复、附件正文、网页、工具结果和推测都不能成为记忆。\n")
                .append("2. 稳定身份、长期目标、长期偏好、原则和底线写入 coreCandidates。核心记忆不设置有效期；只要用户明确表达且未来仍有价值，就应长期保留。\n")
                .append("3. 对未来交流有价值的完整经历写入 episodes：包含发生了什么、用户当时的处境/感受、结果或未决状态；一次性普通问答不要写。occurredAt 使用 yyyy-MM-ddTHH:mm:ss，episodeType 可用 EXPERIENCE、MILESTONE、RELATIONSHIP、DECISION 或 DOCUMENT。\n")
                .append("4. 持续项目、明确尚未结束的任务写入 newWorkItems。用户给出具体日期/时间，或明确相对期限（如明天、下周、本月）时，按当前时间换算 validUntil，使用 yyyy-MM-ddTHH:mm:ss；没有明确期限就留空。\n")
                .append("5. 用户明确说一个已有任务完成、取消或不再需要时，写入 completedWorkItems，existingId 必须来自已有中期记忆。不得因为你猜测或日期临近而标记完成。\n")
                .append("6. 用户明确修正已有事实时，分别使用 coreUpdates 或 workConflicts；系统会留痕，不能新增互相矛盾的旧事实。\n")
                .append("7. 每个候选都要给 importance（1-5）和 confidence（0-100）。只有用户明确表达、未来仍有价值且 confidence 至少 ")
                .append(minConfidence).append(" 的内容才保留；拿不准时返回空数组。\n")
                .append("8. keywords 填 1-").append(maxKeywords)
                .append(" 个便于以后用不同说法找回的短词或短语，不要写推测。\n")
                .append("9. 闲聊、一次性问答和孤立情绪感叹不得新建事实记忆；已完成但具有人生连续性价值的事情可以只写入 episodes。\n")
                .append("10. 对每个新建、更新或完成项，sourceMessageIds 只能从该 user 消息行中方括号给出的 ID 选择。不得编造 ID；无可用 ID 时返回空数组。\n")
                .append("11. 与已有记忆语义重复度超过 ")
                .append(Math.round(dedupThreshold * 100)).append("% 时不新增，把原文放入 duplicates；重复的核心目标仍可放入 coreCandidates 以更新最后确认时间。\n\n");
        prompt.append("已存在的核心记忆：\n");
        if (cores.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (UserCoreMemory memory : cores) {
                prompt.append("- ").append(memory.getContent())
                        .append("；importance=").append(memory.getImportance())
                        .append("；keywords=").append(memory.getKeywords()).append("\n");
            }
        }
        prompt.append("\n已存在的中期记忆（id: 内容；有效期）：\n");
        if (existing.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (UserWorkMemory memory : existing) {
                prompt.append(memory.getId()).append(": ").append(memory.getContent())
                        .append("；validUntil=").append(memory.getValidUntil() == null ? "" : memory.getValidUntil())
                        .append("；importance=").append(memory.getImportance())
                        .append("；keywords=").append(memory.getKeywords()).append("\n");
            }
        }
        prompt.append("\n最近对话：\n");
        for (ContextTurn turn : recent) {
            if ("user".equals(turn.role())) {
                prompt.append("user [sourceMessageIds=")
                        .append(String.join(",", turn.sourceMessageIds()))
                        .append("]: <USER_CONTENT>").append(turn.text()).append("</USER_CONTENT>\n");
            }
        }
        prompt.append("\n只输出 JSON，不要解释：\n")
                .append("{\"episodes\":[{\"title\":\"...\",\"summary\":\"...\",\"episodeType\":\"EXPERIENCE\",\"occurredAt\":\"\",\"importance\":3,\"confidence\":")
                .append(defaultConfidence).append(",\"keywords\":[\"...\"],\"sourceMessageIds\":[\"...\"]}],")
                .append("\"newWorkItems\":[{\"content\":\"...\",\"priority\":").append(defaultPriority)
                .append(",\"importance\":").append(defaultWorkImportance).append(",\"confidence\":")
                .append(defaultConfidence).append(",\"keywords\":[\"...\"],\"validUntil\":\"\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"coreCandidates\":[{\"content\":\"...\",\"importance\":").append(defaultCoreImportance)
                .append(",\"confidence\":")
                .append(defaultConfidence).append(",\"keywords\":[\"...\"],\"sourceMessageIds\":[\"...\"]}],")
                .append("\"coreUpdates\":[{\"oldContent\":\"...\",\"newContent\":\"...\",\"importance\":").append(defaultCoreImportance)
                .append(",\"confidence\":")
                .append(defaultConfidence).append(",\"keywords\":[\"...\"],\"sourceMessageIds\":[\"...\"]}],")
                .append("\"workConflicts\":[{\"existingId\":1,\"existingContent\":\"...\",\"proposedContent\":\"...\",\"importance\":").append(defaultWorkImportance)
                .append(",\"confidence\":")
                .append(defaultConfidence).append(",\"keywords\":[\"...\"],\"validUntil\":\"\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"completedWorkItems\":[{\"existingId\":1,\"reason\":\"...\",\"confidence\":")
                .append(defaultConfidence).append(",\"sourceMessageIds\":[\"...\"]}],\"duplicates\":[\"...\"]}");
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
                    parseWorkConflicts(root), parseCompletedWork(root), parseDuplicates(root));
        } catch (Exception exception) {
            log.warn("解析记忆提取结果失败", exception);
        }
        return new ExtractionResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
