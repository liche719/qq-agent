package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.Memory;
import com.liche.wechatagent.memory.MemoryService;
import com.liche.wechatagent.memory.MemoryRetrievalService;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLoaderTest {

    @Test
    void prioritizesWorkMemoryRelevantToTheCurrentQuestion() {
        MemoryService memoryService = mock(MemoryService.class);
        Memory androidProject = task("我正在做 Android 课程项目", 3);
        Memory photography = task("我喜欢摄影和风景照片", 5);
        when(memoryService.listByKind("u1", Memory.KIND_TASK))
                .thenReturn(List.of(photography, androidProject));
        when(memoryService.listByKind("u1", Memory.KIND_EXPERIENCE)).thenReturn(List.of());
        when(memoryService.listAlwaysInject("u1")).thenReturn(List.of());

        MemoryLoader loader = new MemoryLoader(memoryService, 1, 200);
        MemoryLoader.LoadedMemory loaded = loader.load("u1", "Android 项目现在进展怎么样？");

        assertTrue(loaded.workSection().contains("Android"));
        assertFalse(loaded.workSection().contains("摄影"));
    }

    @Test
    void loadsOnlyCurrentUsersLinkedMediaAndRecordsActualMemoryUse() {
        MemoryService memoryService = mock(MemoryService.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        Memory goal = new Memory("u1", Memory.KIND_PROFILE, "用户正在根据课程表安排本周学习");
        goal.setId(1L);
        goal.setSourceMediaIds("42");
        goal.setLastConfirmedAt(LocalDateTime.now());
        StoredMedia schedule = new StoredMedia();
        schedule.setId(42L);
        schedule.setUserId("u1");
        schedule.setFileName("第5周课程表.png");
        schedule.setSummary("第5周课程与教室安排");
        when(memoryService.listAlwaysInject("u1")).thenReturn(List.of(goal));
        when(memoryService.listByKind("u1", Memory.KIND_TASK)).thenReturn(List.of());
        when(memoryService.listByKind("u1", Memory.KIND_EXPERIENCE)).thenReturn(List.of());
        when(mediaRepository.findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE))
                .thenReturn(List.of(schedule));
        MemoryLoader loader = new MemoryLoader(memoryService, mediaRepository, 5, 500, 5, 500, 15);

        MemoryLoader.LoadedMemory loaded = loader.load("u1", "我今天上什么课？");

        assertTrue(loaded.coreSection().contains("第5周课程表.png"));
        verify(mediaRepository).findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE);
        // 用过就记一次（定向 UPDATE 由 MemoryService.touch 负责）
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Memory>> touched = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(memoryService).touch(touched.capture(), org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(15));
        assertEquals(List.of(1L), touched.getValue().stream().map(Memory::getId).toList());
    }

    @Test
    void delegatesProductionLoadsToDurableRetrievalService() {
        MemoryService memoryService = mock(MemoryService.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        when(retrievalService.retrieve("u1", "历史问题", 5, 500, 4, 400, 7))
                .thenReturn(new MemoryRetrievalService.RetrievedMemory("核心", "历史"));
        MemoryLoader loader = new MemoryLoader(memoryService, mediaRepository, retrievalService,
                4, 400, 5, 500, 7);

        MemoryLoader.LoadedMemory loaded = loader.load("u1", "历史问题");

        assertEquals("核心", loaded.coreSection());
        assertEquals("历史", loaded.workSection());
        verify(retrievalService).retrieve("u1", "历史问题", 5, 500, 4, 400, 7);
    }

    private Memory task(String content, int priority) {
        Memory memory = new Memory("u1", Memory.KIND_TASK, content);
        memory.setPriority(priority);
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
