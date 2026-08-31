package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.agent.ContextTurn;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractorAutonomyTest {

    @Test
    void automaticallyPromotesLongTermGoalWithoutUsingAssistantClaims() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(
                new ContextTurn("user", "我要考南京理工大学研究生"),
                new ContextTurn("assistant", "那我替你决定考北京大学")));
        when(workMemoryService.listActive("u1")).thenReturn(List.of());
        when(coreMemoryService.listActive("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[],"coreCandidates":[{"content":"用户的长期目标是考取南京理工大学研究生"}],
                 "coreUpdates":[],"workConflicts":[],"duplicates":[]}
                """);
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, workMemoryService,
                coreMemoryService, archiveService, new ObjectMapper(), 20);

        extractor.extract("u1");

        verify(coreMemoryService).add(org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("用户的长期目标是考取南京理工大学研究生"),
                org.mockito.ArgumentMatchers.eq("AUTO"), org.mockito.ArgumentMatchers.any(MemoryProvenance.class));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("我要考南京理工大学研究生"));
        assertFalse(prompt.getValue().contains("替你决定考北京大学"));
    }
}
