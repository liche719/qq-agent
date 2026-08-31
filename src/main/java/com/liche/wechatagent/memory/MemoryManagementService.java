package com.liche.wechatagent.memory;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** User-facing memory controls. Every operation is scoped to the current user. */
@Service
public class MemoryManagementService {

    private final UserService userService;
    private final CoreMemoryService coreMemoryService;
    private final WorkMemoryService workMemoryService;
    private final StoredMediaRepository storedMediaRepository;

    @Autowired
    public MemoryManagementService(UserService userService,
                                   CoreMemoryService coreMemoryService,
                                   WorkMemoryService workMemoryService,
                                   StoredMediaRepository storedMediaRepository) {
        this.userService = userService;
        this.coreMemoryService = coreMemoryService;
        this.workMemoryService = workMemoryService;
        this.storedMediaRepository = storedMediaRepository;
    }

    MemoryManagementService(UserService userService,
                            CoreMemoryService coreMemoryService,
                            WorkMemoryService workMemoryService) {
        this(userService, coreMemoryService, workMemoryService, null);
    }

    public String overview(String userId) {
        List<UserCoreMemory> core = coreMemoryService.listActive(userId);
        List<UserWorkMemory> work = workMemoryService.listActive(userId).stream()
                .sorted(Comparator.comparing(UserWorkMemory::getUpdatedAt).reversed())
                .toList();
        List<UserWorkMemory> inactive = workMemoryService.listInactive(userId).stream()
                .sorted(Comparator.comparing(UserWorkMemory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .toList();
        Map<Long, StoredMedia> linkedMedia = linkedMedia(userId, core, work, inactive);
        StringBuilder text = new StringBuilder("自动记忆：")
                .append(userService.isMemoryEnabled(userId) ? "开启" : "关闭")
                .append("\n\n【核心记忆】\n");
        if (core.isEmpty()) {
            text.append("（暂无）\n");
        } else {
            core.forEach(memory -> text.append("C").append(memory.getId()).append("：").append(memory.getContent())
                    .append(metadata(memory, linkedMedia)).append("\n"));
        }
        text.append("\n【工作记忆】\n");
        if (work.isEmpty()) {
            text.append("（暂无）\n");
        } else {
            work.forEach(memory -> text.append("W").append(memory.getId()).append("：").append(memory.getContent())
                    .append(metadata(memory, linkedMedia)).append("\n"));
        }
        if (!inactive.isEmpty()) {
            text.append("\n【近期已结束/过期的工作记忆】\n");
            inactive.forEach(memory -> text.append("W").append(memory.getId()).append("（")
                    .append(displayStatus(memory)).append("）：").append(memory.getContent())
                    .append("\n"));
        }
        text.append("\n用法：/memory on|off；/memory forget C编号 或 W编号");
        return text.toString().trim();
    }

    public String handle(String userId, String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isBlank() || "list".equalsIgnoreCase(trimmed)) {
            return overview(userId);
        }
        if ("on".equalsIgnoreCase(trimmed) || "开启".equals(trimmed)) {
            userService.setMemoryEnabled(userId, true);
            return "自动记忆已开启：系统会自主保存明确、长期有用的信息，不会打断对话询问确认。";
        }
        if ("off".equalsIgnoreCase(trimmed) || "关闭".equals(trimmed)) {
            userService.setMemoryEnabled(userId, false);
            return "自动记忆已关闭。已有记忆会继续供本次和未来对话参考；你可随时用 /memory 查看或删除。";
        }
        if (trimmed.startsWith("forget ") || trimmed.startsWith("删除 ")) {
            return forget(userId, trimmed.substring(trimmed.indexOf(' ') + 1).trim());
        }
        return "用法：/memory 查看；/memory on|off；/memory forget 南京理工。也可以使用 C3、W12 这样的编号。记忆由系统自动提取，无需手动添加。";
    }

    private String forget(String userId, String reference) {
        if (reference == null || reference.trim().length() < 2) {
            throw new BizException("请说清要删除哪条，例如：/memory forget 南京理工");
        }
        String normalized = reference.trim();
        if (!normalized.matches("(?i)[CW]\\s*\\d+")) {
            return forgetByContent(userId, normalized);
        }
        String type = normalized.substring(0, 1).toUpperCase();
        Long id;
        try {
            id = Long.parseLong(normalized.substring(1).trim());
        } catch (NumberFormatException exception) {
            throw new BizException("编号格式不正确，例如：/memory forget C3");
        }
        if ("C".equals(type)) {
            coreMemoryService.delete(userId, id);
        } else if ("W".equals(type)) {
            workMemoryService.forget(userId, id);
        } else {
            throw new BizException("编号应以 C（核心）或 W（工作）开头");
        }
        return "已删除该记忆，操作记录已保留用于审计。";
    }

    private String forgetByContent(String userId, String keyword) {
        String needle = keyword.toLowerCase();
        List<MemoryMatch> matches = new java.util.ArrayList<>();
        coreMemoryService.list(userId).stream()
                .filter(memory -> memory.getContent().toLowerCase().contains(needle))
                .forEach(memory -> matches.add(new MemoryMatch("C", memory.getId(), memory.getContent())));
        allWork(userId).stream()
                .filter(memory -> memory.getContent().toLowerCase().contains(needle))
                .forEach(memory -> matches.add(new MemoryMatch("W", memory.getId(), memory.getContent())));
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
        if ("C".equals(match.type())) {
            coreMemoryService.delete(userId, match.id());
        } else {
            workMemoryService.forget(userId, match.id());
        }
        return "已删除这条记忆：「" + match.content() + "」。操作记录仍保留用于审计。";
    }

    private record MemoryMatch(String type, Long id, String content) {
    }

    private List<UserWorkMemory> allWork(String userId) {
        List<UserWorkMemory> result = new java.util.ArrayList<>(workMemoryService.listActive(userId));
        result.addAll(workMemoryService.listInactive(userId));
        return result;
    }

    private String metadata(UserCoreMemory memory, Map<Long, StoredMedia> linkedMedia) {
        StringBuilder result = new StringBuilder("（来源=").append(blank(memory.getSourceType(), "USER_EXPLICIT"))
                .append("，最后确认=").append(format(memory.getLastConfirmedAt()));
        appendMediaLabels(result, memory.getSourceMediaIds(), linkedMedia);
        return result.append('）').toString();
    }

    private String metadata(UserWorkMemory memory, Map<Long, StoredMedia> linkedMedia) {
        StringBuilder result = new StringBuilder("（");
        if (memory.getValidUntil() != null) {
            result.append("有效至=").append(format(memory.getValidUntil())).append("，");
        }
        result.append("来源=").append(blank(memory.getSourceType(), "USER_EXPLICIT"))
                .append("，最后确认=").append(format(memory.getLastConfirmedAt()));
        appendMediaLabels(result, memory.getSourceMediaIds(), linkedMedia);
        return result.append('）').toString();
    }

    private Map<Long, StoredMedia> linkedMedia(String userId, List<UserCoreMemory> core,
                                                List<UserWorkMemory> work, List<UserWorkMemory> inactive) {
        if (storedMediaRepository == null) {
            return Map.of();
        }
        Set<Long> mediaIds = new LinkedHashSet<>();
        core.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
        work.forEach(memory -> mediaIds.addAll(mediaIds(memory.getSourceMediaIds())));
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

    private String displayStatus(UserWorkMemory memory) {
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
