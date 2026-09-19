package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.agent.ContextTurn;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractorStaleResultTest {

    @Test
    void doesNotWriteAnExtractionThatWasSupersededDuringModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        MemoryService memoryService = mock(MemoryService.class);
        AtomicBoolean current = new AtomicBoolean(true);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(new ContextTurn("user", "我要考南京理工大学研究生")));
        when(memoryService.listActive("u1", Memory.KIND_TASK)).thenReturn(List.of());
        when(memoryService.listAlwaysInject("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenAnswer(invocation -> {
            current.set(false);
            return "{\"newWorkItems\":[],\"coreCandidates\":[{\"content\":\"用户计划考南京理工大学研究生\"}],\"coreUpdates\":[],\"workConflicts\":[],\"completedWorkItems\":[],\"duplicates\":[]}";
        });
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, memoryService,
                new ObjectMapper(), 20);

        extractor.extract("u1", current::get);

        verify(memoryService, never()).addProfile(org.mockito.ArgumentMatchers.anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(MemoryProvenance.class));
        verify(memoryService, never()).addTask(org.mockito.ArgumentMatchers.anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(MemoryProvenance.class),
                org.mockito.ArgumentMatchers.any());
    }
}
