package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

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

    @Test
    void ignoresLowConfidenceCandidatesWithoutWritingMemory() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(
                new ContextTurn("user", "我可能下个月换工作")));
        when(workMemoryService.listActive("u1")).thenReturn(List.of());
        when(coreMemoryService.listActive("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[{"content":"用户下个月会换工作","priority":3,"confidence":20}],
                 "coreCandidates":[],"coreUpdates":[],"workConflicts":[],"completedWorkItems":[],"duplicates":[]}
                """);
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, workMemoryService,
                coreMemoryService, archiveService, new ObjectMapper(), 20);

        extractor.extract("u1");

        verify(workMemoryService, never()).add(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(MemoryProvenance.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void continuesApplyingOtherCandidatesWhenOneMemoryWriteFails() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(
                new ContextTurn("user", "这周完成实验报告，同时我长期目标是考南京理工大学研究生", List.of("msg-1"))));
        when(workMemoryService.listActive("u1")).thenReturn(List.of());
        when(coreMemoryService.listActive("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[{"content":"用户本周完成实验报告","priority":4,"sourceMessageIds":["msg-1"]}],
                 "coreCandidates":[{"content":"用户的长期目标是考南京理工大学研究生","sourceMessageIds":["msg-1"]}],
                 "coreUpdates":[],"workConflicts":[],"completedWorkItems":[],"duplicates":[]}
                """);
        doThrow(new IllegalStateException("temporary database failure")).when(workMemoryService).add(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("用户本周完成实验报告"),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(MemoryProvenance.class),
                org.mockito.ArgumentMatchers.any());
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, workMemoryService,
                coreMemoryService, archiveService, new ObjectMapper(), 20);

        assertTrue(extractor.extract("u1"));

        verify(coreMemoryService).add(org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("用户的长期目标是考南京理工大学研究生"),
                org.mockito.ArgumentMatchers.eq("AUTO"), org.mockito.ArgumentMatchers.any(MemoryProvenance.class));
    }

    @Test
    void appliesConfiguredExtractionLimitsAndPromptPolicy() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextStore contextStore = mock(ContextStore.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        when(contextStore.getRecent("u1", 20)).thenReturn(List.of(new ContextTurn("user", "我有一个长期目标")));
        when(workMemoryService.listActive("u1")).thenReturn(List.of());
        when(coreMemoryService.listActive("u1")).thenReturn(List.of());
        when(chatModel.chat(anyString())).thenReturn("""
                {"newWorkItems":[],"coreCandidates":[
                  {"content":"第一个目标","confidence":90},
                  {"content":"第二个目标","confidence":90}],
                 "coreUpdates":[],"workConflicts":[],"completedWorkItems":[],"duplicates":[]}
                """);
        MemoryPolicyProperties policies = new MemoryPolicyProperties();
        policies.setExtractionMaxCandidates(1);
        policies.setExtractionMaxKeywords(2);
        policies.setExtractionDefaultConfidence(77);
        policies.setDedupThreshold(0.9d);
        MemoryExtractor extractor = new MemoryExtractor(chatModel, contextStore, workMemoryService,
                coreMemoryService, archiveService, new ObjectMapper(), 20, null, new MemoryMutationLock(),
                null, 60, policies, "UTC");

        assertTrue(extractor.extract("u1"));

        verify(coreMemoryService, times(1)).add(eq("u1"), anyString(), eq("AUTO"),
                any(MemoryProvenance.class), any(MemoryAttributes.class));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("keywords 填 1-2 个"));
        assertTrue(prompt.getValue().contains("超过 90%"));
    }
}
