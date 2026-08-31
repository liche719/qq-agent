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
        String type = "archive_summary".equalsIgnoreCase(source) ? "SYSTEM_SUMMARY" : "USER_EXPLICIT";
        return new MemoryProvenance(type, "SYSTEM_SUMMARY".equals(type) ? 70 : 100, List.of(), List.of());
    }

    public static MemoryProvenance userExplicit(List<String> sourceMessageIds, List<Long> sourceMediaIds) {
        return new MemoryProvenance("USER_EXPLICIT", 100, sourceMessageIds, sourceMediaIds);
    }

    public String messageIdsColumn() {
        return String.join("|", sourceMessageIds);
    }

    public String mediaIdsColumn() {
        return sourceMediaIds.stream().map(String::valueOf).reduce((left, right) -> left + "|" + right).orElse("");
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
