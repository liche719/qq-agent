package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.agent.ContextTurn;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractorLifecycleTest {

    @Test
    void recordsExplicitDeadlineAndCompletionWithTrustedSourceMessageIds() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        UserWorkMemory previous = new UserWorkMemory("u1", "完成课程报告", 4, "extraction");
        previous.setId(12L);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(
                new ContextTurn("user", "我会在 2026-09-03 23:59 前完成新的实验报告，之前的课程报告已经交了", List.of("msg-77"))));
        when(workMemoryService.listActive("u1")).thenReturn(List.of(previous));
        when(coreMemoryService.listActive("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[{"content":"用户将在 2026-09-03 前完成实验报告","priority":4,
                "validUntil":"2026-09-03T23:59:00","sourceMessageIds":["msg-77"]}],
                "coreCandidates":[],"coreUpdates":[],"workConflicts":[],
                "completedWorkItems":[{"existingId":12,"reason":"用户明确表示课程报告已经交了","sourceMessageIds":["msg-77"]}],"duplicates":[]}
                """);
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, workMemoryService,
                coreMemoryService, new ObjectMapper(), 20);

        extractor.extract("u1");

        verify(workMemoryService).add(anyString(), anyString(), anyInt(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(provenance -> provenance.sourceMessageIds().equals(List.of("msg-77"))),
                org.mockito.ArgumentMatchers.argThat(deadline -> deadline != null && deadline.equals(LocalDateTime.of(2026, 9, 3, 23, 59))));
        verify(workMemoryService).markCompleted(org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq(12L),
                anyString(), org.mockito.ArgumentMatchers.argThat(provenance -> provenance.sourceMessageIds().equals(List.of("msg-77"))));
    }
}
