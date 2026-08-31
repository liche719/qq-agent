package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryProvenanceTest {

    @Test
    void mergesRepeatedConfirmationsWithoutDiscardingEarlierEvidence() {
        MemoryProvenance existing = MemoryProvenance.fromStored("USER_EXPLICIT", 100,
                "message-1|message-2", "4");
        MemoryProvenance incoming = MemoryProvenance.userExplicit(List.of("message-2", "message-3"), List.of(4L, 8L));

        MemoryProvenance merged = existing.merge(incoming);

        assertEquals("USER_EXPLICIT", merged.sourceType());
        assertEquals(List.of("message-1", "message-2", "message-3"), merged.sourceMessageIds());
        assertEquals(List.of(4L, 8L), merged.sourceMediaIds());
    }
}
