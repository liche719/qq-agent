package com.liche.wechatagent.memory;

import java.util.List;

/** A memory record removed at the user's request, carrying only the evidence needed for cleanup. */
public record ForgottenMemory(String layer, Long id, String content, String source, List<String> sourceMessageIds) {

    public ForgottenMemory {
        layer = layer == null ? "" : layer;
        content = content == null ? "" : content;
        source = source == null ? "" : source;
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }

}
