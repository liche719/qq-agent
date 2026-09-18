package com.liche.wechatagent.memory;

import java.util.List;

/**
 * 提取器产出的一条事实候选（2026-09-18 事实层 v2）。
 *
 * <p>一条一句话：subject（这件事的名字）+ predicate（属性）+ object（值）。
 * 例：subject=第一周周二晚数学课、predicate=教室、object=303。
 *
 * <p>{@link #relation} / {@link #targetSubject} 只在"主调用里直接给了合并结论"时有值
 * （提示词里带了已有事实卡片清单时）。这样**一次调用就能定稿**，不用再为每条事实单独叫一次模型；
 * 卡片清单太长没带时这两个字段为空，写回时就退回"召回 + 单独判定"那条路。
 *
 * @param source           {@link MemoryFact#SOURCE_DOC}/{@link MemoryFact#SOURCE_USER}/{@link MemoryFact#SOURCE_AUTO}
 * @param docMediaId       source=DOC 时指向 stored_media.id（回溯"图上写的是什么"）
 * @param sourceMessageIds 证据消息 id（只能来自提示词里给过的）
 * @param relation         NEW / SUPERSEDES / SUPPLEMENT / SAME；null = 模型没说，走召回+判定
 * @param targetSubject    合并目标"那件事"的名字（必须与已有卡片里的 subject 完全一致）
 */
public record MemoryFactCandidate(String subject, String predicate, String object, String content,
                                  String source, int confidence, Long docMediaId,
                                  List<String> keywords, List<String> sourceMessageIds,
                                  String relation, String targetSubject) {

    public MemoryFactCandidate(String subject, String predicate, String object, String content,
                               String source, int confidence, Long docMediaId,
                               List<String> keywords, List<String> sourceMessageIds) {
        this(subject, predicate, object, content, source, confidence, docMediaId, keywords, sourceMessageIds,
                null, null);
    }

    public MemoryFactCandidate {
        subject = trim(subject, 200);
        predicate = trim(predicate, 64);
        object = trim(object, 500);
        content = trim(content, 1000);
        source = normalizeSource(source);
        keywords = keywords == null ? List.of() : keywords;
        sourceMessageIds = sourceMessageIds == null ? List.of() : sourceMessageIds;
        relation = normalizeRelation(relation);
        targetSubject = trim(targetSubject, 200);
    }

    /** 主调用是否直接给了可用的合并结论（给了就不必再召回 + 单独判定） */
    public boolean hasDecision() {
        return relation != null && (MemoryFact.RELATION_NEW.equals(relation)
                || (targetSubject != null && !targetSubject.isBlank()));
    }

    /** 提示词没给 content 时按 S/P/O 拼一句（不优雅但比空着强） */
    public MemoryFactCandidate withComposedContent() {
        if (content != null && !content.isBlank()) {
            return this;
        }
        String composed = subject + (predicate == null || predicate.isBlank() ? "" : "的" + predicate)
                + "是" + object;
        return new MemoryFactCandidate(subject, predicate, object, composed, source, confidence, docMediaId,
                keywords, sourceMessageIds, relation, targetSubject);
    }

    /** 属性槽的键（subject + predicate），用于批内去重 */
    public String slotKey() {
        return lower(subject) + "\u0000" + lower(predicate);
    }

    public boolean usable() {
        return subject != null && !subject.isBlank() && object != null && !object.isBlank();
    }

    private static String normalizeRelation(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case MemoryFact.RELATION_NEW, MemoryFact.RELATION_SUPERSEDES, MemoryFact.RELATION_SUPPLEMENT,
                 MemoryFact.RELATION_SAME -> upper;
            default -> null;
        };
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
