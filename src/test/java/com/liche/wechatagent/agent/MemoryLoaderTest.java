package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.memory.UserCoreMemory;
import com.liche.wechatagent.memory.MemoryRetrievalService;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLoaderTest {

    @Test
    void prioritizesWorkMemoryRelevantToTheCurrentQuestion() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        UserWorkMemory androidProject = memory("我正在做 Android 课程项目", 3);
        UserWorkMemory photography = memory("我喜欢摄影和风景照片", 5);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());
        when(workRepository.findByUserIdAndArchivedFalse("u1")).thenReturn(List.of(photography, androidProject));

        MemoryLoader loader = new MemoryLoader(coreRepository, workRepository, 1, 200);
        MemoryLoader.LoadedMemory loaded = loader.load("u1", "Android 项目现在进展怎么样？");

        assertTrue(loaded.workSection().contains("Android"));
        assertFalse(loaded.workSection().contains("摄影"));
    }

    @Test
    void loadsOnlyCurrentUsersLinkedMediaAndRecordsActualMemoryUse() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户正在根据课程表安排本周学习");
        goal.setSourceMediaIds("42");
        goal.setLastConfirmedAt(LocalDateTime.now());
        StoredMedia schedule = new StoredMedia();
        schedule.setId(42L);
        schedule.setUserId("u1");
        schedule.setFileName("第5周课程表.png");
        schedule.setSummary("第5周课程与教室安排");
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of(goal));
        when(workRepository.findByUserIdAndArchivedFalse("u1")).thenReturn(List.of());
        when(mediaRepository.findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE))
                .thenReturn(List.of(schedule));
        MemoryLoader loader = new MemoryLoader(coreRepository, workRepository, mediaRepository,
                5, 500, 5, 500, 15);

        MemoryLoader.LoadedMemory loaded = loader.load("u1", "我今天上什么课？");

        assertTrue(loaded.coreSection().contains("第5周课程表.png"));
        assertNotNull(goal.getLastUsedAt());
        verify(mediaRepository).findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE);
    }

    @Test
    void delegatesProductionLoadsToDurableRetrievalService() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        when(retrievalService.retrieve("u1", "历史问题", 5, 500, 4, 400, 7))
                .thenReturn(new MemoryRetrievalService.RetrievedMemory("核心", "历史"));
        MemoryLoader loader = new MemoryLoader(coreRepository, workRepository, mediaRepository, retrievalService,
                4, 400, 5, 500, 7);

        MemoryLoader.LoadedMemory loaded = loader.load("u1", "历史问题");

        assertEquals("核心", loaded.coreSection());
        assertEquals("历史", loaded.workSection());
        verify(retrievalService).retrieve("u1", "历史问题", 5, 500, 4, 400, 7);
    }

    private UserWorkMemory memory(String content, int priority) {
        UserWorkMemory memory = new UserWorkMemory("u1", content, priority, "extraction");
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
