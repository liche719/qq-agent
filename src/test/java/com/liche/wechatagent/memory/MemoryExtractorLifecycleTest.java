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
        MemoryService memoryService = mock(MemoryService.class);
        Memory previous = new Memory("u1", Memory.KIND_TASK, "完成课程报告");
        previous.setId(12L);
        previous.setPriority(4);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(
                new ContextTurn("user", "我会在 2026-09-03 23:59 前完成新的实验报告，之前的课程报告已经交了", List.of("msg-77"))));
        when(memoryService.listActive("u1", Memory.KIND_TASK)).thenReturn(List.of(previous));
        when(memoryService.listAlwaysInject("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[{"content":"用户将在 2026-09-03 前完成实验报告","priority":4,
                "validUntil":"2026-09-03T23:59:00","sourceMessageIds":["msg-77"]}],
                "coreCandidates":[],"coreUpdates":[],"workConflicts":[],
                "completedWorkItems":[{"existingId":12,"reason":"用户明确表示课程报告已经交了","sourceMessageIds":["msg-77"]}],"duplicates":[]}
                """);
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, memoryService,
                new ObjectMapper(), 20);

        extractor.extract("u1");

        verify(memoryService).addTask(anyString(), anyString(), anyInt(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(provenance -> provenance.sourceMessageIds().equals(List.of("msg-77"))),
                org.mockito.ArgumentMatchers.argThat(deadline -> deadline != null && deadline.equals(LocalDateTime.of(2026, 9, 3, 23, 59))));
        verify(memoryService).markCompleted(org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq(12L),
                anyString(), org.mockito.ArgumentMatchers.argThat(provenance -> provenance.sourceMessageIds().equals(List.of("msg-77"))));
        // 写回后仍要跑一次过期扫描（原来挂在工作记忆服务上）
        verify(memoryService).expireDueMemories();
    }
}
