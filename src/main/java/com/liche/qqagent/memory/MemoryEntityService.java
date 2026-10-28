package com.liche.qqagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 实体归一（2026-10-26，P0-5）。
 *
 * <p>只做**保守归并**：规范化后完全一致才算同一件事。规范化 = 去空白、全角转半角、
 * 去掉「·」「-」「_」这类分隔符、去掉括号里的注音。不做模糊/同义匹配。
 *
 * <p>副作用：命中已有实体时把这次的原写法记进 aliases，方便以后人工排查"为什么被并到一起"。
 */
@Service
public class MemoryEntityService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntityService.class);

    private final MemoryEntityRepository repository;

    public MemoryEntityService(MemoryEntityRepository repository) {
        this.repository = repository;
    }

    /** 把模型给的 subject 解析成规范写法；没有就新建一条实体 */
    public String canonicalize(String userId, String subject) {
        String canonical = normalize(subject);
        if (canonical.isEmpty() || userId == null || userId.isBlank()) {
            return subject == null ? "" : subject;
        }
        try {
            Optional<MemoryEntity> existing = repository.findFirstByUserIdAndCanonical(userId, canonical);
            if (existing.isPresent()) {
                MemoryEntity entity = existing.get();
                entity.setFactCount(entity.getFactCount() + 1);
                entity.setUpdatedAt(LocalDateTime.now());
                rememberAlias(entity, subject);
                repository.save(entity);
                return entity.getCanonical();
            }
            MemoryEntity created = new MemoryEntity();
            created.setUserId(userId);
            created.setCanonical(canonical);
            created.setAliases(subject == null ? null : subject.trim());
            created.setFactCount(1);
            created.setCreatedAt(LocalDateTime.now());
            created.setUpdatedAt(LocalDateTime.now());
            repository.save(created);
            return canonical;
        } catch (RuntimeException exception) {
            // 归一失败不能让写事实失败
            log.warn("实体归一失败，退回原写法 subject={}: {}", subject, exception.getMessage());
            return subject == null ? "" : subject;
        }
    }



    /**
     * 只读归一（2026-10-28）：把 subject 解析成规范写法，**不写库、不涨 factCount**。
     *
     * <p>给排序用——查询路径不能有副作用，否则每召回一次就给实体表打卡。
     * 查不到时返回归一后的写法（和 {@link #canonicalize} 首次写入时的结果一致）。
     */
    public String canonicalizeReadOnly(String userId, String subject) {
        String canonical = normalize(subject);
        if (canonical.isEmpty() || userId == null || userId.isBlank()) {
            return subject == null ? "" : subject;
        }
        try {
            return repository.findFirstByUserIdAndCanonical(userId, canonical)
                    .map(MemoryEntity::getCanonical)
                    .orElse(canonical);
        } catch (RuntimeException exception) {
            return canonical;
        }
    }

    /**
     * 只读：这段话里出现了哪些**已有实体**（2026-10-28 新增）。
     *
     * <p>给检索加权用：用户问"周三·课几点"时，如果 `周三·课` 是 `周三课` 的别名，
     * 那么 subject 为 `周三课` 的事实应该排到前面——但向量相似度不一定能把它们顶上来
     * （别名多了几个字符，embedding 就被拉偏）。
     *
     * <p>与 {@link #canonicalize} 的区别：**不写库、不涨 factCount**，查询路径必须用这个。
     */
    public java.util.Set<String> mentioned(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) {
            return java.util.Set.of();
        }
        String normalizedText = normalize(text);
        if (normalizedText.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> hits = new java.util.LinkedHashSet<>();
        try {
            for (MemoryEntity entity : repository.findByUserIdOrderByFactCountDescUpdatedAtDesc(
                    userId, org.springframework.data.domain.PageRequest.of(0, 200))) {
                String canonical = entity.getCanonical();
                if (canonical != null && !canonical.isEmpty() && normalizedText.contains(normalize(canonical))) {
                    hits.add(canonical);
                    continue;
                }
                String aliases = entity.getAliases();
                if (aliases == null || aliases.isBlank()) {
                    continue;
                }
                for (String alias : aliases.split(",")) {
                    String normalizedAlias = normalize(alias);
                    if (!normalizedAlias.isEmpty() && normalizedText.contains(normalizedAlias)) {
                        hits.add(canonical);
                        break;
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("实体命中查询失败（忽略，不影响召回）：{}", exception.getMessage());
        }
        return hits;
    }

    /** 给提示词用的"已有实体写法"清单（按事实数排序） */
    public List<String> topCanonicals(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            return repository.findByUserIdOrderByFactCountDescUpdatedAtDesc(userId,
                            PageRequest.of(0, Math.max(1, Math.min(50, limit))))
                    .stream().map(MemoryEntity::getCanonical).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 规范化：全角转半角、去掉空白与常见分隔符、**去掉括号字符但保留括号里的内容**
     * （「物理课（A班）」和「物理课（B班）」不能并成一件）。
     * 例：「周二晚·课程」→「周二晚课程」；「 10-28 周三 体育课 」→「1028周三体育课」。
     */
    static String normalize(String subject) {
        if (subject == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(subject.length());
        for (char ch : subject.toCharArray()) {
            char converted = ch >= '\uFF01' && ch <= '\uFF5E' ? (char) (ch - 0xFEE0) : ch;
            if (Character.isWhitespace(converted)) {
                continue;
            }
            if (converted == '\u00B7' || converted == '-' || converted == '_' || converted == '/'
                    || converted == '\u3001' || converted == ',' || converted == '\uFF0C'
                    || converted == ':' || converted == '\uFF1A' || converted == '.') {
                continue;
            }
            if (converted == '\uFF08' || converted == '\uFF09' || converted == '(' || converted == ')'
                    || converted == '\u3010' || converted == '\u3011' || converted == '[' || converted == ']') {
                continue;
            }
            text.append(converted);
        }
        return text.toString();
    }

    private void rememberAlias(MemoryEntity entity, String rawSubject) {
        if (rawSubject == null || rawSubject.isBlank()) {
            return;
        }
        String normalized = normalize(rawSubject);
        if (normalized.equals(entity.getCanonical())) {
            return;
        }
        String aliases = entity.getAliases() == null ? "" : entity.getAliases();
        if (aliases.contains(normalized)) {
            return;
        }
        String merged = aliases.isBlank() ? normalized : aliases + "|" + normalized;
        entity.setAliases(merged.length() > 2000 ? merged.substring(0, 2000) : merged);
    }
}
