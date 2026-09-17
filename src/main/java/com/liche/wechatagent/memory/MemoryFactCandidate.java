package com.liche.wechatagent.memory;

import java.util.List;

/**
 * 提取器产出的一条事实候选（2026-09-18 事实层 v2）。
 *
 * <p>一条一句话：subject（这件事的名字）+ predicate（属性）+ object（值）。
 * 例：subject=第一周周二晚数学课、predicate=教室、object=303。
 *
 * @param source           {@link MemoryFact#SOURCE_DOC}/{@link MemoryFact#SOURCE_USER}/{@link MemoryFact#SOURCE_AUTO}
 * @param docMediaId       source=DOC 时指向 stored_media.id（回溯"图上写的是什么"）
 * @param sourceMessageIds 证据消息 id（只能来自提示词里给过的）
 */
public record MemoryFactCandidate(String subject, String predicate, String object, String content,
                                  String source, int confidence, Long docMediaId,
                                  List<String> keywords, List<String> sourceMessageIds) {

    public MemoryFactCandidate {
        subject = trim(subject, 200);
        predicate = trim(predicate, 64);
        object = trim(object, 500);
        content = trim(content, 1000);
        source = normalizeSource(source);
        keywords = keywords == null ? List.of() : keywords;
        sourceMessageIds = sourceMessageIds == null ? List.of() : sourceMessageIds;
    }

    /** 提示词没给 content 时按 S/P/O 拼一句（不优雅但比空着强） */
    public MemoryFactCandidate withComposedContent() {
        if (content != null && !content.isBlank()) {
            return this;
        }
        String composed = subject + (predicate == null || predicate.isBlank() ? "" : "的" + predicate)
                + "是" + object;
        return new MemoryFactCandidate(subject, predicate, object, composed, source, confidence, docMediaId,
                keywords, sourceMessageIds);
    }

    /** 属性槽的键（subject + predicate），用于批内去重 */
    public String slotKey() {
        return lower(subject) + "\u0000" + lower(predicate);
    }

    public boolean usable() {
        return subject != null && !subject.isBlank() && object != null && !object.isBlank();
    }

    private static String normalizeSource(String value) {
        if (value == null) {
            return MemoryFact.SOURCE_AUTO;
        }
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case MemoryFact.SOURCE_DOC, MemoryFact.SOURCE_USER -> upper;
            default -> MemoryFact.SOURCE_AUTO;
        };
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        // 坑 40：截断要为省略号留一位，否则"配到列宽上限"的写入会整条失败
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
