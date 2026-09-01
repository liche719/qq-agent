package com.liche.wechatagent.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record MemoryProvenance(String sourceType, int confidence, List<String> sourceMessageIds, List<Long> sourceMediaIds) {

    public MemoryProvenance {
        sourceType = sourceType == null || sourceType.isBlank() ? "USER_EXPLICIT" : sourceType.trim().toUpperCase(Locale.ROOT);
        confidence = Math.max(0, Math.min(100, confidence));
        sourceMessageIds = normalizedStrings(sourceMessageIds);
        sourceMediaIds = normalizedLongs(sourceMediaIds);
    }

    public static MemoryProvenance automatic(String source) {
        String normalized = source == null ? "" : source.trim();
        if ("archive_summary".equalsIgnoreCase(normalized)) {
            return new MemoryProvenance("SYSTEM_SUMMARY", 70, List.of(), List.of());
        }
        if ("user_explicit".equalsIgnoreCase(normalized) || "confirm".equalsIgnoreCase(normalized)) {
            return new MemoryProvenance("USER_EXPLICIT", 100, List.of(), List.of());
        }
        return new MemoryProvenance("USER_DERIVED", 85, List.of(), List.of());
    }

    public static MemoryProvenance userExplicit(List<String> sourceMessageIds, List<Long> sourceMediaIds) {
        return new MemoryProvenance("USER_EXPLICIT", 100, sourceMessageIds, sourceMediaIds);
    }

    public static MemoryProvenance fromStored(String sourceType, Integer confidence,
                                              String sourceMessageIds, String sourceMediaIds) {
        return new MemoryProvenance(sourceType, confidence == null ? 100 : confidence,
                splitStrings(sourceMessageIds), splitLongs(sourceMediaIds));
    }

    public MemoryProvenance merge(MemoryProvenance newer) {
        if (newer == null) {
            return this;
        }
        List<String> messages = new ArrayList<>(sourceMessageIds);
        messages.addAll(newer.sourceMessageIds());
        List<Long> media = new ArrayList<>(sourceMediaIds);
        media.addAll(newer.sourceMediaIds());
        return new MemoryProvenance(preferredSourceType(sourceType, newer.sourceType()),
                Math.max(confidence, newer.confidence()), messages, media);
    }

    public String messageIdsColumn() {
        return String.join("|", sourceMessageIds);
    }

    public String mediaIdsColumn() {
        return sourceMediaIds.stream().map(String::valueOf).reduce((left, right) -> left + "|" + right).orElse("");
    }

    private static String preferredSourceType(String current, String newer) {
        if ("USER_EXPLICIT".equalsIgnoreCase(current) || "USER_EXPLICIT".equalsIgnoreCase(newer)) {
            return "USER_EXPLICIT";
        }
        return newer == null || newer.isBlank() ? current : newer;
    }

    private static List<String> splitStrings(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split("\\|"));
    }

    private static List<Long> splitLongs(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String value : stored.split("\\|")) {
            try {
                result.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static List<String> normalizedStrings(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String normalized = value.replace('|', '_').trim();
                if (!normalized.isBlank()) {
                    unique.add(normalized.length() > 120 ? normalized.substring(0, 120) : normalized);
                }
                if (unique.size() >= 20) {
                    break;
                }
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static List<Long> normalizedLongs(List<Long> values) {
        Set<Long> unique = new LinkedHashSet<>();
        if (values != null) {
            for (Long value : values) {
                if (value != null && value > 0) {
                    unique.add(value);
                }
                if (unique.size() >= 20) {
                    break;
                }
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
