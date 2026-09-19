package com.liche.wechatagent.memory;

import com.liche.wechatagent.user.UserService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MemoryExtractionSchedulerTest {

    @Test
    void retriesTransientExtractionFailuresWithoutWaitingForAnotherUserMessage() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            MemoryExtractor extractor = org.mockito.Mockito.mock(MemoryExtractor.class);
            UserService userService = org.mockito.Mockito.mock(UserService.class);
            when(userService.isMemoryEnabled("user-a")).thenReturn(true);
            // 提取入口带了"审计来源"参数（trigger），调度器走的是 4 参重载——这个用例原来钉的是 3 参版本，
            // 桩从来没匹配上（mock 恒返回 false），于是"第一次失败、第二次成功"的语义其实没被真正验证过。
            when(extractor.extract(org.mockito.ArgumentMatchers.eq("user-a"), any(), any(), any()))
                    .thenReturn(false, true);

            MemoryExtractionScheduler scheduler = new MemoryExtractionScheduler(
                    executor, extractor, userService, 0, 0, 1, 0);
            scheduler.schedule("user-a");

            verify(extractor, timeout(1_000).times(2))
                    .extract(org.mockito.ArgumentMatchers.eq("user-a"), any(), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }
}
