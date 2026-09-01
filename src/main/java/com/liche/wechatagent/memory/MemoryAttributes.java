package com.liche.wechatagent.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record MemoryAttributes(int importance, int confidence, List<String> keywords) {

    public MemoryAttributes {
        importance = Math.max(1, Math.min(5, importance));
        confidence = Math.max(0, Math.min(100, confidence));
        keywords = normalizeKeywords(keywords);
    }

    public static MemoryAttributes defaults() {
        return new MemoryAttributes(3, 85, List.of());
    }

    public static MemoryAttributes coreDefaults() {
        return new MemoryAttributes(5, 85, List.of());
    }

    public static MemoryAttributes fromStored(Integer importance, Integer confidence, String keywords) {
        return new MemoryAttributes(importance == null ? 3 : importance,
                confidence == null ? 85 : confidence,
                splitKeywords(keywords));
    }

    public MemoryAttributes merge(MemoryAttributes newer) {
        if (newer == null) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(keywords);
        merged.addAll(newer.keywords());
        return new MemoryAttributes(Math.max(importance, newer.importance()),
                Math.max(confidence, newer.confidence()), new ArrayList<>(merged));
    }

    public String keywordsColumn() {
        return String.join("|", keywords);
    }

    private static List<String> normalizeKeywords(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String item = value.trim().toLowerCase(Locale.ROOT).replace('|', ' ');
                if (!item.isBlank()) {
                    normalized.add(item.length() > 64 ? item.substring(0, 64) : item);
                }
                if (normalized.size() >= 16) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> splitKeywords(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split("\\|"));
    }
}
