package com.liche.wechatagent.memory;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-facing memory controls. Every operation is scoped to the current user. */
@Service
public class MemoryManagementService {

    private static final Pattern FORGET_ARGUMENT = Pattern.compile(
            "(?i)^(?:forget|delete)\\s+(.+)$");
    private static final List<String> CHINESE_FORGET_PREFIXES = List.of("删除", "忘记", "移除", "清除");
    private static final List<String> FUZZY_REFERENCE_NOISE = List.of(
            "请帮我", "帮我", "之前的", "相关的", "我的", "用户的", "我之前", "我想", "我要",
            "删除", "忘记", "移除", "清除", "这条", "那条", "记忆", "内容", "信息", "关于", "相关");
    private static final int MIN_DISTINCTIVE_ANCHOR_LENGTH = 4;
    private static final int MIN_SHARED_BIGRAMS = 3;
    private static final double MIN_FUZZY_SIMILARITY = 0.55d;
    /** 三表合并（2026-09-18）后编号统一成 M&lt;编号&gt;；C/W 只作旧编号的提示 */
    private static final String MEMORY_REFERENCE = "(?i)M\\s*\\d+";
    private static final String LEGACY_REFERENCE = "(?i)[CW]\\s*\\d+";
    private static final String RENUMBERED_HINT =
            "编号形式已改：原来按 C（核心）/W（工作）区分，现在记忆合并成一张表、统一用 M 编号。请发送 /memory 查看新的 M 编号。";

    private final UserService userService;
    private final MemoryService memoryService;
    private final StoredMediaRepository storedMediaRepository;
    private final MemoryContentSimilarity contentSimilarity;
    private final MemoryForgetService memoryForgetService;

    @Autowired
    public MemoryManagementService(UserService userService,
                                   MemoryService memoryService,
                                   StoredMediaRepository storedMediaRepository,
                                   MemoryContentSimilarity contentSimilarity,
                                   MemoryForgetService memoryForgetService) {
        this.userService = userService;
        this.memoryService = memoryService;
        this.storedMediaRepository = storedMediaRepository;
        this.contentSimilarity = contentSimilarity;
        this.memoryForgetService = memoryForgetService;
    }

    public String overview(String userId) {
        List<Memory> profile = sortedActive(userId, Memory.KIND_PROFILE);
        List<Memory> task = sortedActive(userId, Memory.KIND_TASK);
        List<Memory> experience = sortedActive(userId, Memory.KIND_EXPERIENCE);
        // "近期已结束/过期"原来只指中期事项（core/episode 没有结束这一说），这里保持同一口径
        List<Memory> inactive = memoryService.listInactive(userId).stream()
                .filter(memory -> Memory.KIND_TASK.equals(memory.getKind()))
                .sorted(Comparator.comparing(Memory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .toList();
        Map<Long, StoredMedia> linkedMedia = linkedMedia(userId, profile, task, experience, inactive);
        StringBuilder text = new StringBuilder("自动记忆：")
                .append(userService.isMemoryEnabled(userId) ? "开启" : "关闭")
                .append("\n\n【长期设定】\n");
        appendSection(text, profile, linkedMedia);
        text.append("\n【中期事项】\n");
        appendSection(text, task, linkedMedia);
        text.append("\n【经历】\n");
        appendSection(text, experience, linkedMedia);
        if (!inactive.isEmpty()) {
            text.append("\n【近期已结束/过期】\n");
            inactive.forEach(memory -> text.append("M").append(memory.getId()).append("（")
                    .append(displayStatus(memory)).append("）：").append(memory.getContent())
                    .append("\n"));
        }
        text.append("\n用法：/memory on|off；/memory forget M编号");
        return text.toString().trim();
    }

    public String handle(String userId, String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isBlank() || "list".equalsIgnoreCase(trimmed)) {
            return overview(userId);
        }
        if ("on".equalsIgnoreCase(trimmed) || "开启".equals(trimmed)) {
            userService.setMemoryEnabled(userId, true);
            return "自动记忆已开启：系统会自主保存明确、长期有用的信息，不会打断对话询问确认。";
        }
        if ("off".equalsIgnoreCase(trimmed) || "关闭".equals(trimmed)) {
            userService.setMemoryEnabled(userId, false);
            return "自动记忆已关闭。之后的新对话不会再写入长期记忆或持久化对话证据；已有记忆会继续供本次和未来对话参考，你可随时用 /memory 查看或删除。";
        }
        String reference = forgetReference(trimmed);
        if (reference != null) {
            return forget(userId, reference);
        }
        return "用法：/memory 查看；/memory on|off；/memory forget 关键词。也可以使用 M3、M12 这样的编号。记忆由系统自动提取，无需手动添加。";
    }

    private String forget(String userId, String reference) {
        if (reference == null || reference.strip().length() < 2) {
            throw new BizException("请说清要删除哪条，例如：/memory forget 关键词");
        }
        String normalized = reference.strip();
        if (normalized.matches(LEGACY_REFERENCE)) {
            return RENUMBERED_HINT;
        }
        if (!normalized.matches(MEMORY_REFERENCE)) {
            return forgetByContent(userId, normalized);
        }
        Long id;
        try {
            id = Long.parseLong(normalized.substring(1).trim());
        } catch (NumberFormatException exception) {
            throw new BizException("编号格式不正确，例如：/memory forget M3");
        }
        return forgetMemory(userId, "M", id);
    }

    private String forgetByContent(String userId, String keyword) {
        List<MemoryMatch> matches = findMatches(userId, keyword, this::hasExactReferenceMatch);
        if (matches.isEmpty()) {
            matches = findMatches(userId, keyword, this::hasReliableFuzzyReferenceMatch);
        }
        if (matches.isEmpty()) {
            return "没有找到包含「" + keyword + "」的记忆。你可以先发送 /memory 查看。";
        }
        if (matches.size() > 1) {
            StringBuilder text = new StringBuilder("找到了多条相关记忆，为避免删错，请指定其中一个：\n");
            matches.forEach(match -> text.append(match.type()).append(match.id()).append("：")
                    .append(match.content()).append('\n'));
            text.append("例如：/memory forget ").append(matches.get(0).type()).append(matches.get(0).id());
            return text.toString();
        }
        MemoryMatch match = matches.get(0);
        return forgetMemory(userId, "M", match.id());
    }

    private String forgetMemory(String userId, String referenceType, Long id) {
        MemoryForgetService.ForgetOutcome outcome = memoryForgetService.forget(userId, referenceType, id);
        String text = "已彻底遗忘该记忆：它不会再作为长期记忆或关联短期上下文提供给 Agent；审计记录不保留正文。";
        if (outcome.removedRecordCount() > 1) {
            text += "同时清理了 " + (outcome.removedRecordCount() - 1) + " 条关联记录。";
        }
        if (outcome.legacyContextReset()) {
            text += "这条旧记忆没有来源标记，为避免它被重新提取，当前短期会话上下文也已清理。";
        }
        if (!outcome.backupsComplete()) {
            text += "但未能确认全部历史本机备份已清理。";
        }
        return text;
    }

    private String forgetReference(String args) {
        Matcher matcher = FORGET_ARGUMENT.matcher(args);
        if (matcher.matches()) {
            return matcher.group(1).strip();
        }
        for (String prefix : CHINESE_FORGET_PREFIXES) {
            if (args.startsWith(prefix)) {
                return args.substring(prefix.length()).strip();
            }
        }
        return null;
    }

    private List<MemoryMatch> findMatches(String userId, String keyword, MemoryReferenceMatcher matcher) {
        List<MemoryMatch> matches = new ArrayList<>();
        memoryService.list(userId).stream()
                .filter(memory -> matcher.matches(keyword, memory.getContent()))
                .forEach(memory -> matches.add(new MemoryMatch("M", memory.getId(), memory.getContent())));
        return matches;
    }

    private boolean hasExactReferenceMatch(String reference, String content) {
        String normalizedReference = normalizeForComparison(reference);
        String normalizedContent = normalizeForComparison(content);
        return !normalizedReference.isBlank() && !normalizedContent.isBlank()
                && (normalizedContent.contains(normalizedReference) || normalizedReference.contains(normalizedContent));
    }

    private boolean hasReliableFuzzyReferenceMatch(String reference, String content) {
        String normalizedReference = normalizeForFuzzyComparison(reference);
        String normalizedContent = normalizeForFuzzyComparison(content);
        if (normalizedReference.length() < 3 || normalizedContent.length() < 3) {
            return false;
        }
        if (longestDistinctiveAnchor(normalizedReference, normalizedContent) >= MIN_DISTINCTIVE_ANCHOR_LENGTH) {
            return true;
        }
        return sharedBigramCount(normalizedReference, normalizedContent) >= MIN_SHARED_BIGRAMS
                && contentSimilarity.similarity(normalizedReference, normalizedContent) >= MIN_FUZZY_SIMILARITY;
    }

    private int longestDistinctiveAnchor(String reference, String content) {
        int maximumLength = Math.min(16, reference.length());
        for (int length = maximumLength; length >= MIN_DISTINCTIVE_ANCHOR_LENGTH; length--) {
            for (int start = 0; start + length <= reference.length(); start++) {
                if (content.contains(reference.substring(start, start + length))) {
                    return length;
                }
            }
        }
        return 0;
    }

    private int sharedBigramCount(String first, String second) {
        Set<String> firstBigrams = bigrams(first);
        Set<String> secondBigrams = bigrams(second);
        firstBigrams.retainAll(secondBigrams);
        return firstBigrams.size();
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private String normalizeForFuzzyComparison(String value) {
        String normalized = normalizeForComparison(value);
        for (String noise : FUZZY_REFERENCE_NOISE) {
            normalized = normalized.replace(noise, "");
        }
        return normalized;
    }

    private String normalizeForComparison(String value) {
        return value == null ? "" : value.toLowerCase()
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }

    private record MemoryMatch(String type, Long id, String content) {
    }

    @FunctionalInterface
    private interface MemoryReferenceMatcher {
        boolean matches(String reference, String content);
    }

    private List<Memory> sortedActive(String userId, String kind) {
        return memoryService.listActive(userId, kind).stream()
                .sorted(Comparator.comparing(Memory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void appendSection(StringBuilder text, List<Memory> memories, Map<Long, StoredMedia> linkedMedia) {
        if (memories.isEmpty()) {
            text.append("（暂无）\n");
            return;
        }
        memories.forEach(memory -> text.append("M").append(memory.getId()).append("：").append(memory.getContent())
                .append(metadata(memory, linkedMedia)).append("\n"));
    }

    private String metadata(Memory memory, Map<Long, StoredMedia> linkedMedia) {
        StringBuilder result = new StringBuilder("（");
        if (Memory.KIND_TASK.equals(memory.getKind()) && memory.getValidUntil() != null) {
            result.append("有效至=").append(format(memory.getValidUntil())).append("，");
        }
        result.append("来源=").append(blank(memory.getSourceType(), "USER_EXPLICIT"))
                .append("，最后确认=").append(format(memory.getLastConfirmedAt()));
        appendMediaLabels(result, memory.getSourceMediaIds(), linkedMedia);
        return result.append('）').toString();
    }

    private Map<Long, StoredMedia> linkedMedia(String userId, List<Memory> profile, List<Memory> task,
                                                List<Memory> experience, List<Memory> inactive) {
        if (storedMediaRepository == null) {
            return Map.of();
        }
        Set<Long> mediaIds = new LinkedHashSet<>();
        profile.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
        task.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
        experience.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
        inactive.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoredMedia> result = new HashMap<>();
        storedMediaRepository.findByUserIdAndIdInAndStatus(userId, List.copyOf(mediaIds), StoredMedia.ACTIVE)
                .forEach(media -> result.put(media.getId(), media));
        return result;
    }

    private void appendMediaLabels(StringBuilder result, String sourceMediaIds,
                                   Map<Long, StoredMedia> linkedMedia) {
        List<Long> ids = mediaIds(sourceMediaIds);
        if (ids.isEmpty()) {
            return;
        }
        List<String> labels = ids.stream().map(linkedMedia::get).filter(media -> media != null)
                .map(media -> "#" + media.getId() + " " + media.getFileName())
                .toList();
        if (labels.isEmpty()) {
            result.append("，关联资料 #").append(sourceMediaIds.replace('|', '、'));
            return;
        }
        result.append("，关联资料 ").append(String.join("、", labels));
    }

    private List<Long> mediaIds(String sourceMediaIds) {
        return MemoryProvenance.fromStored("USER_EXPLICIT", 100, "", sourceMediaIds).sourceMediaIds();
    }

    private String displayStatus(Memory memory) {
        if (memory.getStatus() == null || memory.getStatus().isBlank()) {
            return memory.getValidUntil() != null && memory.getValidUntil().isBefore(LocalDateTime.now()) ? "已过期" : "已结束";
        }
        return switch (memory.getStatus()) {
            case "COMPLETED" -> "已完成";
            case "EXPIRED" -> "已过期";
            case "SUPERSEDED" -> "已替代";
            default -> "已结束";
        };
    }

    private String format(LocalDateTime value) {
        return value == null ? "未知" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
