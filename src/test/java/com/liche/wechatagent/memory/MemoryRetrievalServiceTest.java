package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    private static final float[] QUERY_VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    /** 2026-09-18（P2/P3）：三处"按向量挑"的层都需要一个可用的向量客户端，测试里统一给个桩。 */
    private MemoryRetrievalService service(UserCoreMemoryRepository coreRepository,
                                           UserWorkMemoryRepository workRepository,
                                           ConversationMemoryService conversationService,
                                           EpisodicMemoryService episodicService) {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embedOne(anyString())).thenReturn(QUERY_VECTOR);
        WorkMemoryVectorStore workVectorStore = mock(WorkMemoryVectorStore.class);
        when(workVectorStore.scores(anyString(), any())).thenReturn(Map.of());
        return new MemoryRetrievalService(coreRepository, workRepository, conversationService, episodicService, null,
                new MemoryPolicyProperties(), embeddingClient, workVectorStore, 8, 3, 0.45d, 0.45d, 0.45d, 6);
    }

    private List<ConversationMemoryService.ConversationHit> hits(ConversationMemory... records) {
        List<ConversationMemoryService.ConversationHit> hits = new ArrayList<>();
        for (ConversationMemory record : records) {
            hits.add(new ConversationMemoryService.ConversationHit(record, 0.72d));
        }
        return hits;
    }

    @Test
    void recallsDurableConversationOutsideTheShortContextWindow() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        List<ConversationMemory> records = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            records.add(new ConversationMemory("u1", "user", "event-" + index,
                    index == 0 ? "我已经确定明年准备南京理工大学研究生考试" : "普通聊天内容" + index,
                    List.of("message-" + index), LocalDateTime.now().minusDays(30L - index), null));
        }
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(records.get(0)));
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前说过的南京理工考试计划",
                4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("南京理工大学研究生考试"));
    }

    @Test
    void neverRendersEvidenceReturnedForAnotherUser() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        ConversationMemory foreign = new ConversationMemory("u2", "user", "event-2", "其他用户的秘密",
                List.of(), LocalDateTime.now(), null);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(foreign));
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的秘密",
                4, 1000, 4, 1000);

        assertFalse(result.workSection().contains("其他用户的秘密"));
    }

    @Test
    void expiredWorkMemoryIsOfferedOnlyAsHistoricalContext() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        UserWorkMemory expired = new UserWorkMemory("u1", "南京理工大学考研旧复习安排", 3, "extraction");
        expired.setId(7L);
        // 2026-09-18：没有 archived 这回事了（归档机制整块删除、存量已恢复活跃），
        // 用"已完成"来表达"只在历史上下文里出现"
        expired.setStatus(MemoryStatus.COMPLETED.name());
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(expired));
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble())).thenReturn(List.of());
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的南京理工复习安排",
                4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("南京理工大学考研旧复习安排"));
    }

    @Test
    void capsHistoricalTextWithinTheWorkMemoryBudget() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        ConversationMemory evidence = new ConversationMemory("u1", "user", "old",
                "南京理工".repeat(200), List.of(), LocalDateTime.now().minusMonths(1), null);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(evidence));
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "之前的南京理工",
                4, 1000, 4, 180);

        assertTrue(result.workSection().length() <= 180);
        assertTrue(result.workSection().contains("南京理工"));
    }

    @Test
    void avoidsInjectingOldConversationWhenAnActiveMemoryAlreadyAnswersTheCurrentQuestion() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考南京理工大学研究生");
        goal.setId(1L);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of(goal));
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, null);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "南京理工考研目标",
                4, 1000, 4, 1000);

        assertTrue(result.coreSection().contains("南京理工大学研究生"));
        verify(conversationService, never()).searchByVector(anyString(), any(), anyInt(), anyDouble());
    }

    @Test
    void retrievesRelevantEpisodeWithoutLeakingAnotherUsersExperience() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        EpisodicMemoryService episodicService = mock(EpisodicMemoryService.class);
        EpisodicMemory owned = new EpisodicMemory("u1", "申请表提醒失误",
                "用户曾因打印申请表的提醒执行异常而着急，希望重要提醒可靠确认", "EXPERIENCE",
                5, 90, LocalDateTime.of(2026, 9, 2, 9, 0), MemoryProvenance.userExplicit(List.of("m1"), List.of()));
        EpisodicMemory foreign = new EpisodicMemory("u2", "其他人的申请表", "其他用户的私密经历",
                "EXPERIENCE", 5, 90, LocalDateTime.now(), MemoryProvenance.userExplicit(List.of("m2"), List.of()));
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());
        // 2026-09-18（P3）：情景记忆改成按向量筛（rankByVector），租户隔离仍由检索服务这道过滤器兜住
        when(episodicService.rankByVector(anyString(), any(), anyDouble())).thenReturn(List.of(owned, foreign));
        MemoryRetrievalService service = service(coreRepository, workRepository, conversationService, episodicService);

        MemoryRetrievalService.RetrievedMemory result = service.retrieve("u1", "上次打印申请表为什么着急",
                4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("申请表提醒失误"));
        assertFalse(result.workSection().contains("其他用户的私密经历"));
    }
}
