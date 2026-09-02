package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class EpisodicMemoryServiceTest {

    @Test
    void persistsEpisodeWithTimeAndTraceableSources() {
        EpisodicMemoryRepository repository = mock(EpisodicMemoryRepository.class);
        when(repository.findByUserIdAndStatusOrderByOccurredAtDesc(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq(MemoryStatus.ACTIVE.name()),
                any(Pageable.class)))
                .thenReturn(List.of());
        EpisodicMemoryService service = new EpisodicMemoryService(repository, new MemoryContentSimilarity(0.8));
        LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 2, 9, 30);

        service.add("u1", "打印申请表", "用户因申请表未及时打印而着急，要求重要提醒可靠确认",
                "milestone", 5, 92, List.of("申请表", "提醒"), occurredAt,
                MemoryProvenance.userExplicit(List.of("message-1"), List.of(8L)));

        ArgumentCaptor<EpisodicMemory> saved = ArgumentCaptor.forClass(EpisodicMemory.class);
        verify(repository).save(saved.capture());
        assertEquals("u1", saved.getValue().getUserId());
        assertEquals("MILESTONE", saved.getValue().getEpisodeType());
        assertEquals(occurredAt, saved.getValue().getOccurredAt());
        assertEquals("message-1", saved.getValue().getSourceMessageIds());
        assertEquals("8", saved.getValue().getSourceMediaIds());
    }

    @Test
    void updatesEquivalentEpisodeInsteadOfCreatingASecondCopy() {
        EpisodicMemoryRepository repository = mock(EpisodicMemoryRepository.class);
        EpisodicMemory existing = new EpisodicMemory("u1", "旧标题",
                "用户因为申请表没有打印而很着急，希望提醒更可靠", "EXPERIENCE",
                4, 85, LocalDateTime.of(2026, 9, 1, 10, 0),
                MemoryProvenance.userExplicit(List.of("old"), List.of()));
        when(repository.findByUserIdAndStatusOrderByOccurredAtDesc(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq(MemoryStatus.ACTIVE.name()),
                any(Pageable.class)))
                .thenReturn(List.of(existing));
        EpisodicMemoryService service = new EpisodicMemoryService(repository, new MemoryContentSimilarity(0.6));

        service.add("u1", "申请表提醒", "用户因为申请表没有打印而很着急，希望提醒更可靠",
                "EXPERIENCE", 5, 95, List.of("申请表"), LocalDateTime.of(2026, 9, 2, 10, 0),
                MemoryProvenance.userExplicit(List.of("new"), List.of()));

        verify(repository).save(existing);
        assertEquals("old|new", existing.getSourceMessageIds());
        assertEquals(95, existing.getConfidence());
    }

    @Test
    void touchesOnlyEpisodesOutsideTheWriteThrottleWindow() {
        EpisodicMemoryRepository repository = mock(EpisodicMemoryRepository.class);
        EpisodicMemory stale = new EpisodicMemory("u1", "旧经历", "一段需要刷新的长期经历摘要",
                "EXPERIENCE", 3, 85, LocalDateTime.now(), MemoryProvenance.automatic("extraction"));
        EpisodicMemory recent = new EpisodicMemory("u1", "新经历", "一段刚刚使用过的长期经历摘要",
                "EXPERIENCE", 3, 85, LocalDateTime.now(), MemoryProvenance.automatic("extraction"));
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 15, 0);
        stale.setLastUsedAt(now.minusHours(1));
        recent.setLastUsedAt(now.minusMinutes(2));
        EpisodicMemoryService service = new EpisodicMemoryService(repository, new MemoryContentSimilarity(0.8));

        service.touch(List.of(stale, recent), now, 15);

        verify(repository).saveAll(List.of(stale));
        assertEquals(now, stale.getLastUsedAt());
        assertEquals(now.minusMinutes(2), recent.getLastUsedAt());
    }
}
