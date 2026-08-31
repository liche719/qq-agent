package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryArchiveServiceTest {

    @Test
    void doesNotArchiveSummariesOrItemsWithExplicitDeadlines() {
        ChatModel chatModel = mock(ChatModel.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserWorkMemory summary = new UserWorkMemory("u1", "已归档的旧项目摘要", 1, "archive_summary");
        UserWorkMemory deadline = new UserWorkMemory("u1", "明天提交实验报告", 1, "extraction");
        deadline.setValidUntil(LocalDateTime.now().plusDays(1));
        when(workMemoryService.countActive("u1")).thenReturn(21L);
        when(workRepository.findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(summary, deadline));
        MemoryArchiveService service = new MemoryArchiveService(chatModel, workRepository, archiveRepository,
                workMemoryService, new ObjectMapper(), changeLogRepository, new MemoryMutationLock(), 20, 10);

        service.compressIfNeeded("u1");

        verify(chatModel, never()).chat(anyString());
    }

    @Test
    void deletingAnArchiveSummaryAlsoForgetsItsHiddenSourceMemories() {
        ChatModel chatModel = mock(ChatModel.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        MemoryArchive archive = new MemoryArchive("u1", "旧项目摘要", "[8]");
        archive.setId(30L);
        UserWorkMemory original = new UserWorkMemory("u1", "旧项目原始内容", 2, "extraction");
        original.setId(8L);
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(archive));
        when(workRepository.findById(8L)).thenReturn(Optional.of(original));
        when(workMemoryService.forget("u1", 8L))
                .thenReturn(new ForgottenMemory("WORK", 8L, "旧项目原始内容", "extraction", List.of("m-8")));
        MemoryArchiveService service = new MemoryArchiveService(chatModel, workRepository, archiveRepository,
                workMemoryService, new ObjectMapper(), changeLogRepository, new MemoryMutationLock(), 20, 10);

        List<ForgottenMemory> forgotten = service.forgetSummary("u1", "旧项目摘要");

        assertEquals(2, forgotten.size());
        verify(workMemoryService).forget("u1", 8L);
        verify(archiveRepository).delete(archive);
        verify(changeLogRepository).redactContentForMemory("u1", "ARCHIVE", 30L);
    }

    @Test
    void deletingAnArchivedSourceRemovesTheActiveSummaryWithoutDeletingOtherSources() {
        ChatModel chatModel = mock(ChatModel.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        MemoryArchive archive = new MemoryArchive("u1", "旧项目合并摘要", "[8,9]");
        archive.setId(31L);
        UserWorkMemory summary = new UserWorkMemory("u1", "旧项目合并摘要", 3, "archive_summary");
        summary.setId(99L);
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(archive));
        when(workRepository.findByUserIdAndArchivedFalse("u1")).thenReturn(List.of(summary));
        when(workMemoryService.forget("u1", 99L))
                .thenReturn(new ForgottenMemory("WORK", 99L, "旧项目合并摘要", "archive_summary", List.of()));
        MemoryArchiveService service = new MemoryArchiveService(chatModel, workRepository, archiveRepository,
                workMemoryService, new ObjectMapper(), changeLogRepository, new MemoryMutationLock(), 20, 10);

        List<ForgottenMemory> forgotten = service.forgetSourceMemory("u1", 8L);

        assertEquals(2, forgotten.size());
        verify(workMemoryService).forget("u1", 99L);
        verify(workMemoryService, never()).forget("u1", 8L);
        verify(workMemoryService, never()).forget("u1", 9L);
        verify(archiveRepository).delete(archive);
    }
}
