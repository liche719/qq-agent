package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void persistsEvidenceWithUserScopeAndRetentionDeadline() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        when(repository.existsByUserIdAndEventKey("u1", "event-1")).thenReturn(false);
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 30, 500);
        LocalDateTime created = LocalDateTime.of(2026, 9, 1, 10, 0);

        service.record("u1", "user", "event-1", "我要考南京理工大学研究生",
                List.of("message-1"), created);

        ArgumentCaptor<ConversationMemory> saved = ArgumentCaptor.forClass(ConversationMemory.class);
        verify(repository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("user", saved.getValue().getRole());
        assertEquals(List.of("message-1"), saved.getValue().sourceMessageIdList());
        assertEquals(created.plusDays(30), saved.getValue().getExpiresAt());
    }

    @Test
    void retrievesOnlyCurrentUserEvidenceAndKeepsChronologicalExtractionOrder() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        ConversationMemory newest = new ConversationMemory("u1", "assistant", "e2", "回复",
                List.of("m2"), LocalDateTime.of(2026, 9, 1, 10, 2), null);
        ConversationMemory oldest = new ConversationMemory("u1", "user", "e1", "用户陈述",
                List.of("m1"), LocalDateTime.of(2026, 9, 1, 10, 1), null);
        ConversationMemory foreign = new ConversationMemory("u2", "user", "e3", "其他用户",
                List.of("m3"), LocalDateTime.of(2026, 9, 1, 10, 3), null);
        // 提取窗口现在只查 user/assistant 行（工具调用以 system 角色写两条记录，不能占掉窗口），
        // 但仍然保留 foreign 行来验证"仓库就算返回别人的行也不会被用"
        when(repository.findByUserIdAndRoleInOrderByCreatedAtDesc(eq("u1"), any(), any(Pageable.class)))
                .thenReturn(List.of(newest, foreign, oldest));
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 0, 500);

        var turns = service.recentForExtraction("u1", 20);

        assertEquals(List.of("用户陈述", "回复"), turns.stream().map(turn -> turn.text()).toList());
        assertTrue(turns.stream().allMatch(turn -> !turn.text().contains("其他用户")));
    }

    @Test
    void forgetsLegacyEvidenceByContentWithoutTouchingAnotherUser() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        ConversationMemory owned = new ConversationMemory("u1", "user", "e1",
                "我要考南京理工大学研究生", List.of(), LocalDateTime.now(), null);
        owned.setId(1L);
        ConversationMemory foreign = new ConversationMemory("u2", "user", "e2",
                "我要考南京理工大学研究生", List.of(), LocalDateTime.now(), null);
        foreign.setId(2L);
        when(repository.findByUserIdAndIdGreaterThanOrderByIdAsc(eq("u1"), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(owned, foreign));
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 0, 500);

        assertEquals(1, service.forgetContent("u1", "用户的长期目标是考南京理工大学研究生"));
        verify(repository).deleteAll(List.of(owned));
    }

    @Test
    void forgetsEvidenceByMessageIdWithoutLoadingAnotherUsersEntireHistory() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        ConversationMemory matching = new ConversationMemory("u1", "user", "e1", "用户的私密信息",
                List.of("m-1"), LocalDateTime.now(), null);
        matching.setId(1L);
        ConversationMemory unrelated = new ConversationMemory("u1", "assistant", "e2", "普通回复",
                List.of("m-2"), LocalDateTime.now(), null);
        unrelated.setId(2L);
        when(repository.findByUserIdAndIdGreaterThanOrderByIdAsc(eq("u1"), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(matching, unrelated));
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 0, 500);

        assertEquals(1, service.forgetSourceMessageIds("u1", List.of("m-1")));

        verify(repository).deleteAll(List.of(matching));
    }

    // 2026-09-18（P3）：原来这里有两个用例测"按字面词 LIKE 捞旧对话"（relevantForRetrieval）——
    // 那条路已整块删除（对无关问题也会塞满 1500 字旧对话），现在按向量取，测这两个用例的前提不存在了。

    @Test
    void purgesExpiredEvidenceWithOneBoundedDatabaseOperation() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        when(repository.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(3);
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 30, 500);

        assertEquals(3, service.purgeExpired());

        verify(repository).deleteExpiredBefore(any(LocalDateTime.class));
    }

    @Test
    void buildsChronologicalContextWithinBudgetAndExcludesToolEvents() {
        ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
        ConversationMemory newest = new ConversationMemory("u1", "assistant", "a2", "最终回复",
                List.of("m2"), LocalDateTime.of(2026, 9, 2, 10, 3), null);
        ConversationMemory tool = new ConversationMemory("u1", "system", "tool", "工具内部结果",
                List.of(), LocalDateTime.of(2026, 9, 2, 10, 2), null);
        ConversationMemory oldest = new ConversationMemory("u1", "user", "u1", "用户问题",
                List.of("m1"), LocalDateTime.of(2026, 9, 2, 10, 1), null);
        when(repository.findByUserIdOrderByCreatedAtDesc(eq("u1"), any(Pageable.class)))
                .thenReturn(List.of(newest, tool, oldest));
        ConversationMemoryService service = new ConversationMemoryService(repository, 20, 50, 0, 500);

        var turns = service.recentForContext("u1", 20, 500);

        assertEquals(List.of("用户问题", "最终回复"), turns.stream().map(turn -> turn.text()).toList());
    }
}
