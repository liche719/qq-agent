package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆组装的用例（2026-09-18 三表合并后：所有散文记忆都来自 {@code memory} 表，按 kind 分派）。
 */
class MemoryRetrievalServiceTest {

    private static final float[] QUERY_VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    /** 三处"按向量挑"的层都需要一个可用的向量客户端，测试里统一给个桩 */
    private MemoryRetrievalService service(MemoryService memoryService,
                                           ConversationMemoryService conversationService) {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embedOne(anyString())).thenReturn(QUERY_VECTOR);
        return new MemoryRetrievalService(memoryService, conversationService, null, embeddingClient,
                0.45d, 0.45d, 0.45d, 6);
    }

    private Memory memory(String userId, String kind, String content, Long id) {
        Memory memory = new Memory(userId, kind, content);
        memory.setId(id);
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private List<ConversationMemoryService.ConversationHit> hits(ConversationMemory... records) {
        return Arrays.stream(records)
                .map(record -> new ConversationMemoryService.ConversationHit(record, 0.72d))
                .toList();
    }

    private ConversationMemory conversation(String userId, String content, LocalDateTime at) {
        return new ConversationMemory(userId, "user", "event-" + content.hashCode(), content,
                List.of("message"), at, null);
    }

    @Test
    void recallsDurableConversationOutsideTheShortContextWindow() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(conversation("u1", "我已经确定明年准备南京理工大学研究生考试",
                        LocalDateTime.now().minusDays(30))));

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "之前说过的南京理工考试计划", 4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("南京理工大学研究生考试"));
    }

    @Test
    void neverRendersEvidenceReturnedForAnotherUser() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(conversation("u2", "其他用户的秘密", LocalDateTime.now())));

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "之前的秘密", 4, 1000, 4, 1000);

        assertFalse(result.workSection().contains("其他用户的秘密"));
    }

    @Test
    void expiredWorkMemoryIsOfferedOnlyAsHistoricalContext() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        // 2026-09-18：没有 archived 这回事了，用"已完成"表达"只在历史上下文里出现"
        Memory completed = memory("u1", Memory.KIND_TASK, "南京理工大学考研旧复习安排", 7L);
        completed.setStatus(MemoryStatus.COMPLETED.name());
        when(memoryService.list("u1")).thenReturn(List.of(completed));
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "之前的南京理工复习安排", 4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("南京理工大学考研旧复习安排"));
    }

    @Test
    void capsHistoricalTextWithinTheWorkMemoryBudget() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        when(conversationService.searchByVector(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(hits(conversation("u1", "南京理工".repeat(200), LocalDateTime.now().minusMonths(1))));

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "之前的南京理工", 4, 1000, 4, 180);

        assertTrue(result.workSection().length() <= 180);
        assertTrue(result.workSection().contains("南京理工"));
    }

    @Test
    void avoidsInjectingOldConversationWhenAnActiveMemoryAlreadyAnswersTheCurrentQuestion() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        when(memoryService.listAlwaysInject("u1"))
                .thenReturn(List.of(memory("u1", Memory.KIND_PROFILE, "用户的长期目标是考南京理工大学研究生", 1L)));

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "南京理工考研目标", 4, 1000, 4, 1000);

        assertTrue(result.coreSection().contains("南京理工大学研究生"));
        verify(conversationService, never()).searchByVector(anyString(), any(), anyInt(), anyDouble());
    }

    @Test
    void retrievesRelevantEpisodeWithoutLeakingAnotherUsersExperience() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversationService = mock(ConversationMemoryService.class);
        Memory owned = memory("u1", Memory.KIND_EXPERIENCE,
                "用户曾因打印申请表的提醒执行异常而着急，希望重要提醒可靠确认", 11L);
        owned.setTitle("申请表提醒失误");
        owned.setOccurredAt(LocalDateTime.of(2026, 9, 2, 9, 0));
        Memory foreign = memory("u2", Memory.KIND_EXPERIENCE, "其他用户的私密经历", 12L);
        foreign.setTitle("其他人的申请表");
        when(memoryService.rankByVector(eq("u1"), any(), anyDouble(), eq(Memory.KIND_EXPERIENCE)))
                .thenReturn(List.of(owned, foreign));

        MemoryRetrievalService.RetrievedMemory result = service(memoryService, conversationService)
                .retrieve("u1", "上次打印申请表为什么着急", 4, 1000, 4, 1000);

        assertTrue(result.workSection().contains("申请表提醒失误"));
        assertFalse(result.workSection().contains("其他用户的私密经历"));
    }
}
