package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.log.UserScope;
import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private final ChatModel chatModel;
    private final ContextStore contextStore;
    private final WorkMemoryService workMemoryService;
    private final CoreMemoryService coreMemoryService;
    private final MemoryArchiveService archiveService;
    private final ObjectMapper objectMapper;
    private final int recentTurns;
    private final StoredMediaRepository storedMediaRepository;
    private final MemoryMutationLock mutationLock;

    @Autowired
    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           @Value("${memory.extraction-recent-turns:6}") int recentTurns,
                           StoredMediaRepository storedMediaRepository,
                           MemoryMutationLock mutationLock) {
        this.chatModel = chatModel;
        this.contextStore = contextStore;
        this.workMemoryService = workMemoryService;
        this.coreMemoryService = coreMemoryService;
        this.archiveService = archiveService;
        this.objectMapper = objectMapper;
        this.recentTurns = recentTurns;
        this.storedMediaRepository = storedMediaRepository;
        this.mutationLock = mutationLock;
    }

    public MemoryExtractor(ChatModel chatModel,
                           ContextStore contextStore,
                           WorkMemoryService workMemoryService,
                           CoreMemoryService coreMemoryService,
                           MemoryArchiveService archiveService,
                           ObjectMapper objectMapper,
                           int recentTurns) {
        this(chatModel, contextStore, workMemoryService, coreMemoryService, archiveService, objectMapper,
                recentTurns, null, new MemoryMutationLock());
    }

    public boolean extract(String userId) {
        return extract(userId, () -> true);
    }

    /**
     * 只允许仍属于当前静默窗口的提取结果写回。模型调用无法强制取消，
     * 但在持久化前再次核验即可避免旧会话覆盖新事实。
     */
    public boolean extract(String userId, BooleanSupplier stillCurrent) {
        MDC.put("userScope", UserScope.forUser(userId));
        try {
            if (!stillCurrent.getAsBoolean()) {
                return true;
            }
            List<ContextTurn> recent = contextStore.getRecent(userId, recentTurns);
            if (recent.isEmpty()) {
                return true;
            }
            List<UserWorkMemory> existing = workMemoryService.listActive(userId);
            List<UserCoreMemory> cores = coreMemoryService.listActive(userId);
            ExtractionResult result = parse(chatModel.chat(buildPrompt(recent, existing, cores)));
            log.info("记忆提取完成 user={} work={} core={} coreUpdates={} workUpdates={} completed={} duplicates={}", userId,
                    result.newWork().size(), result.coreCandidates().size(), result.coreUpdates().size(),
                    result.conflicts().size(), result.completedWork().size(), result.duplicates().size());
            if (!stillCurrent.getAsBoolean()) {
                log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                return true;
            }
            mutationLock.runExclusive(userId, () -> {
                if (!stillCurrent.getAsBoolean()) {
                    log.info("记忆提取结果已过期，放弃写回 user={}", userId);
                    return;
                }
                apply(userId, result, recent);
            });
            return true;
        } catch (Exception e) {
            log.warn("记忆提取失败 user={}", userId, e);
            return false;
        } finally {
            MDC.remove("userScope");
        }
    }

    public record NewWork(String content, int priority, String validUntil, List<String> sourceMessageIds) {
        public NewWork(String content, int priority) {
            this(content, priority, "", List.of());
        }

        public NewWork {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record CoreCandidate(String content, List<String> sourceMessageIds) {
        public CoreCandidate(String content) {
            this(content, List.of());
        }

        public CoreCandidate {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record CoreUpdate(String oldContent, String newContent, List<String> sourceMessageIds) {
        public CoreUpdate(String oldContent, String newContent) {
            this(oldContent, newContent, List.of());
        }

        public CoreUpdate {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record WorkConflict(Long existingId, String existingContent, String proposedContent, String validUntil,
                               List<String> sourceMessageIds) {
        public WorkConflict(Long existingId, String existingContent, String proposedContent) {
            this(existingId, existingContent, proposedContent, "", List.of());
        }

        public WorkConflict {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record WorkCompletion(Long existingId, String reason, List<String> sourceMessageIds) {
        public WorkCompletion {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record ExtractionResult(List<NewWork> newWork, List<CoreCandidate> coreCandidates,
                                   List<CoreUpdate> coreUpdates, List<WorkConflict> conflicts,
                                   List<WorkCompletion> completedWork, List<String> duplicates) {
    }

    private void apply(String userId, ExtractionResult result, List<ContextTurn> recent) {
        Set<String> allowedSourceIds = allowedSourceIds(recent);
        for (NewWork candidate : result.newWork()) {
            if (candidate.content() == null || candidate.content().isBlank() || isDuplicate(candidate.content(), result.duplicates())) {
                continue;
            }
            workMemoryService.add(userId, candidate.content(), candidate.priority(), "extraction", "AUTO",
                    provenance(userId, candidate.sourceMessageIds(), allowedSourceIds), parseValidUntil(candidate.validUntil()));
        }
        for (CoreCandidate candidate : result.coreCandidates()) {
            if (candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }
            coreMemoryService.add(userId, candidate.content(), "AUTO",
                    provenance(userId, candidate.sourceMessageIds(), allowedSourceIds));
        }
        for (CoreUpdate candidate : result.coreUpdates()) {
            if (candidate.oldContent() == null || candidate.oldContent().isBlank()
                    || candidate.newContent() == null || candidate.newContent().isBlank()) {
                continue;
            }
            coreMemoryService.listActive(userId).stream()
                    .filter(memory -> memory.getContent() != null
                            && (memory.getContent().contains(candidate.oldContent())
                            || candidate.oldContent().contains(memory.getContent())))
                    .findFirst()
                    .ifPresent(memory -> coreMemoryService.update(userId, memory.getId(), candidate.newContent(),
                            "用户明确陈述的新长期事实替代旧事实", "AUTO",
                            provenance(userId, candidate.sourceMessageIds(), allowedSourceIds)));
        }
        for (WorkConflict candidate : result.conflicts()) {
            if (candidate.existingId() == null || candidate.proposedContent() == null || candidate.proposedContent().isBlank()) {
                continue;
            }
            workMemoryService.updateFromExtraction(userId, candidate.existingId(), candidate.proposedContent(),
                    provenance(userId, candidate.sourceMessageIds(), allowedSourceIds), parseValidUntil(candidate.validUntil()));
        }
        for (WorkCompletion completion : result.completedWork()) {
            if (completion.existingId() == null) {
                continue;
            }
            workMemoryService.markCompleted(userId, completion.existingId(), completion.reason(),
                    provenance(userId, completion.sourceMessageIds(), allowedSourceIds));
        }
        workMemoryService.expireDueMemories();
        archiveService.compressIfNeeded(userId);
    }

    private MemoryProvenance provenance(String userId, List<String> requestedSourceIds, Set<String> allowedSourceIds) {
        List<String> messageIds = requestedSourceIds == null ? List.of() : requestedSourceIds.stream()
                .filter(allowedSourceIds::contains)
                .distinct()
                .toList();
        if (messageIds.isEmpty()) {
            messageIds = List.copyOf(allowedSourceIds);
        }
        List<Long> mediaIds = new ArrayList<>();
        if (storedMediaRepository != null && !messageIds.isEmpty()) {
            try {
                mediaIds = storedMediaRepository.findByUserIdAndSourceMessageIdInAndStatus(userId, messageIds, StoredMedia.ACTIVE)
                        .stream().map(StoredMedia::getId).filter(id -> id != null).distinct().toList();
            } catch (Exception exception) {
                log.debug("无法解析记忆关联资料 user={}: {}", userId, exception.getMessage());
            }
        }
        return MemoryProvenance.userExplicit(messageIds, mediaIds);
    }

    private Set<String> allowedSourceIds(List<ContextTurn> recent) {
        Set<String> result = new LinkedHashSet<>();
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
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("。只从最近对话中提取值得长期保留的信息。\n\n");
        prompt.append("规则：\n")
                .append("1. 只能依据 user 角色的明确陈述；assistant 回复、附件正文、网页、工具结果和推测都不能成为记忆。\n")
                .append("2. 稳定身份、长期目标、长期偏好、原则和底线写入 coreCandidates。核心记忆不设置有效期；例如‘我要考南京理工大学研究生’必须长期保留。\n")
                .append("3. 持续项目、明确尚未结束的任务写入 newWorkItems。用户给出具体日期/时间，或明确相对期限（如明天、下周、本月）时，按当前时间换算 validUntil，使用 yyyy-MM-ddTHH:mm:ss；没有明确期限就留空。\n")
                .append("4. 用户明确说一个已有任务完成、取消或不再需要时，写入 completedWorkItems，existingId 必须来自已有中期记忆。不得因为你猜测或日期临近而标记完成。\n")
                .append("5. 用户明确修正已有事实时，分别使用 coreUpdates 或 workConflicts；系统会留痕，不能新增互相矛盾的旧事实。\n")
                .append("6. 闲聊、一次性问答、情绪感叹、无明确期限的临时打算、已完成事项不得新建记忆。拿不准时返回空数组。\n")
                .append("7. 对每个新建、更新或完成项，sourceMessageIds 只能从该 user 消息行中方括号给出的 ID 选择。不得编造 ID；无可用 ID 时返回空数组。\n")
                .append("8. 与已有记忆语义重复度超过 80% 时不新增，把原文放入 duplicates；重复的核心目标仍可放入 coreCandidates 以更新最后确认时间。\n\n");
        prompt.append("已存在的核心记忆：\n");
        if (cores.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (UserCoreMemory memory : cores) {
                prompt.append("- ").append(memory.getContent()).append("\n");
            }
        }
        prompt.append("\n已存在的中期记忆（id: 内容；有效期）：\n");
        if (existing.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (UserWorkMemory memory : existing) {
                prompt.append(memory.getId()).append(": ").append(memory.getContent())
                        .append("；validUntil=").append(memory.getValidUntil() == null ? "" : memory.getValidUntil()).append("\n");
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
                .append("{\"newWorkItems\":[{\"content\":\"...\",\"priority\":3,\"validUntil\":\"\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"coreCandidates\":[{\"content\":\"...\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"coreUpdates\":[{\"oldContent\":\"...\",\"newContent\":\"...\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"workConflicts\":[{\"existingId\":1,\"existingContent\":\"...\",\"proposedContent\":\"...\",\"validUntil\":\"\",\"sourceMessageIds\":[\"...\"]}],")
                .append("\"completedWorkItems\":[{\"existingId\":1,\"reason\":\"...\",\"sourceMessageIds\":[\"...\"]}],\"duplicates\":[\"...\"]}");
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
        List<NewWork> newWork = new ArrayList<>();
        List<CoreCandidate> coreCandidates = new ArrayList<>();
        List<CoreUpdate> coreUpdates = new ArrayList<>();
        List<WorkConflict> conflicts = new ArrayList<>();
        List<WorkCompletion> completedWork = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(text);
            for (JsonNode node : root.path("newWorkItems")) {
                newWork.add(new NewWork(node.path("content").asText(""), node.path("priority").asInt(3),
                        node.path("validUntil").asText(""), stringList(node.path("sourceMessageIds"))));
            }
            for (JsonNode node : root.path("coreCandidates")) {
                coreCandidates.add(new CoreCandidate(node.path("content").asText(""), stringList(node.path("sourceMessageIds"))));
            }
            for (JsonNode node : root.path("coreUpdates")) {
                coreUpdates.add(new CoreUpdate(node.path("oldContent").asText(""), node.path("newContent").asText(""),
                        stringList(node.path("sourceMessageIds"))));
            }
            for (JsonNode node : root.path("workConflicts")) {
                conflicts.add(new WorkConflict(node.path("existingId").isMissingNode() ? null : node.path("existingId").asLong(),
                        node.path("existingContent").asText(""), node.path("proposedContent").asText(""),
                        node.path("validUntil").asText(""), stringList(node.path("sourceMessageIds"))));
            }
            for (JsonNode node : root.path("completedWorkItems")) {
                completedWork.add(new WorkCompletion(node.path("existingId").isMissingNode() ? null : node.path("existingId").asLong(),
                        node.path("reason").asText(""), stringList(node.path("sourceMessageIds"))));
            }
            for (JsonNode node : root.path("duplicates")) {
                duplicates.add(node.asText(""));
            }
        } catch (Exception exception) {
            log.warn("解析记忆提取结果失败", exception);
        }
        return new ExtractionResult(newWork, coreCandidates, coreUpdates, conflicts, completedWork, duplicates);
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }
}
