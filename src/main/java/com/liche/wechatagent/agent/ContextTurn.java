package com.liche.wechatagent.agent;

import java.util.List;

public record ContextTurn(String role, String text, List<String> sourceMessageIds) {

    public ContextTurn(String role, String text) {
        this(role, text, List.of());
    }

    public ContextTurn {
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }
}
