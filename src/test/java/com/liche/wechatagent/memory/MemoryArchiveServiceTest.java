package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

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
        UserWorkMemory summary = new UserWorkMemory("u1", "已归档的旧项目摘要", 1, "archive_summary");
        UserWorkMemory deadline = new UserWorkMemory("u1", "明天提交实验报告", 1, "extraction");
        deadline.setValidUntil(LocalDateTime.now().plusDays(1));
        when(workMemoryService.countActive("u1")).thenReturn(21L);
        when(workRepository.findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(summary, deadline));
        MemoryArchiveService service = new MemoryArchiveService(chatModel, workRepository, archiveRepository,
                workMemoryService, new ObjectMapper(), 20, 10);

        service.compressIfNeeded("u1");

        verify(chatModel, never()).chat(anyString());
    }
}
