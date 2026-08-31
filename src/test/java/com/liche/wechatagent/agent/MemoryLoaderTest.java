package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

    private UserWorkMemory memory(String content, int priority) {
        UserWorkMemory memory = new UserWorkMemory("u1", content, priority, "extraction");
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
