package com.liche.qqagent.memory;

import com.liche.qqagent.config.MemoryPolicyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MemoryContentSimilarity {

    private static final List<String> DEFAULT_NEGATION_MARKERS = List.of(
            "不再", "不想", "不会", "取消", "放弃", "停止", "改考", "不喜欢", "不需要");

    private final double threshold;
    private final List<String> negationMarkers;

    @Autowired
    public MemoryContentSimilarity(MemoryPolicyProperties policyProperties) {
        this(policyProperties == null ? 0.8d : policyProperties.getDedupThreshold(),
                policyProperties == null ? DEFAULT_NEGATION_MARKERS : policyProperties.getNegationMarkers());
    }

    public MemoryContentSimilarity(double threshold) {
        this(threshold, DEFAULT_NEGATION_MARKERS);
    }

    MemoryContentSimilarity(double threshold, List<String> negationMarkers) {
        this.threshold = Double.isFinite(threshold) && threshold >= 0.60d && threshold <= 0.98d
                ? threshold : 0.8d;
        this.negationMarkers = negationMarkers == null || negationMarkers.isEmpty()
                ? DEFAULT_NEGATION_MARKERS : List.copyOf(negationMarkers);
    }

    /** 相似度分数（2026-10-28 对外暴露，给"相似但不同"这一档用） */
    public double score(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.isBlank() || right.isBlank()) {
            return 0d;
        }
        if (left.equals(right)) {
            return 1d;
        }
        if (hasOppositePolarity(left, right)) {
            // 极性相反（"不再…"/"改为…"）不该被当成重复，也不该被当成"同一件事"
            return 0d;
        }
        return similarity(left, right);
    }

    public double threshold() {
        return threshold;
    }

    public boolean isDuplicate(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        if (hasOppositePolarity(left, right) || Math.min(left.length(), right.length()) < 8) {
            return false;
        }
        if (left.contains(right) || right.contains(left)) {
            return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length()) >= threshold;
        }
        return similarity(left, right) >= threshold;
    }

    double similarity(String first, String second) {
        Set<String> left = bigrams(first);
        Set<String> right = bigrams(second);
        if (left.isEmpty() || right.isEmpty()) {
            return 0d;
        }
        Set<String> overlap = new HashSet<>(left);
        overlap.retainAll(right);
        return (2d * overlap.size()) / (left.size() + right.size());
    }

    private boolean hasOppositePolarity(String left, String right) {
        return containsNegation(left) != containsNegation(right);
    }

    private boolean containsNegation(String value) {
        return negationMarkers.stream().anyMatch(value::contains);
    }

    private Set<String> bigrams(String value) {
        String normalized = normalize(value);
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 1 < normalized.length(); index++) {
            result.add(normalized.substring(index, index + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }
}
