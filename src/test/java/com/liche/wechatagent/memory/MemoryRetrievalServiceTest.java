package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    @Test
    void recallsDurableConversationOutsideTheShortContextWindow() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        List<ConversationMemory> records = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            records.add(new ConversationMemory("u1", "user", "event-" + index,
                    index == 0 ? "我已经确定明年准备南京理工大学研究生考试" : "普通聊天内容" + index,
                    List.of("message-" + index), LocalDateTime.now().minusDays(30L - index), null));
        }
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.recentForRetrieval("u1")).thenReturn(records);
        MemoryRetrievalService service = new MemoryRetrievalService(coreRepository, workRepository,
                archiveRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前说过的南京理工考试计划",
                4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("南京理工大学研究生考试"));
    }

    @Test
    void neverRendersEvidenceReturnedForAnotherUser() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        ConversationMemory foreign = new ConversationMemory("u2", "user", "event-2", "其他用户的秘密",
                List.of(), LocalDateTime.now(), null);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.recentForRetrieval("u1")).thenReturn(List.of(foreign));
        MemoryRetrievalService service = new MemoryRetrievalService(coreRepository, workRepository,
                archiveRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的秘密",
                4, 1000, 4, 1000);

        assertFalse(result.workSection().contains("其他用户的秘密"));
    }

    @Test
    void makesArchivedWorkAvailableOnlyAsHistoricalContext() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        UserWorkMemory archived = new UserWorkMemory("u1", "南京理工大学考研旧复习安排", 3, "extraction");
        archived.setId(7L);
        archived.setArchived(true);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(archived));
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.relevantForRetrieval("u1", "之前的南京理工复习安排"))
                .thenReturn(List.of());
        when(conversationService.recentForRetrieval("u1")).thenReturn(List.of());
        MemoryRetrievalService service = new MemoryRetrievalService(coreRepository, workRepository,
                archiveRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的南京理工复习安排",
                4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("[已归档] 南京理工大学考研旧复习安排"));
    }

    @Test
    void capsHistoricalTextWithinTheWorkMemoryBudget() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        ConversationMemory evidence = new ConversationMemory("u1", "user", "old",
                "南京理工".repeat(200), List.of(), LocalDateTime.now().minusMonths(1), null);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.relevantForRetrieval("u1", "之前的南京理工"))
                .thenReturn(List.of(evidence));
        MemoryRetrievalService service = new MemoryRetrievalService(coreRepository, workRepository,
                archiveRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的南京理工",
                4, 1000, 4, 180);

        assertTrue(result.workSection().length() <= 180);
        assertTrue(result.workSection().contains("南京理工"));
    }

    @Test
    void avoidsInjectingOldConversationWhenAnActiveMemoryAlreadyAnswersTheCurrentQuestion() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archiveRepository = mock(MemoryArchiveRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考南京理工大学研究生");
        goal.setId(1L);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of(goal));
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(archiveRepository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
        MemoryRetrievalService service = new MemoryRetrievalService(coreRepository, workRepository,
                archiveRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "南京理工考研目标",
                4, 1000, 4, 1000);

        assertTrue(result.coreSection().contains("南京理工大学研究生"));
        verify(conversationService, never()).relevantForRetrieval("u1", "南京理工考研目标");
    }
}
