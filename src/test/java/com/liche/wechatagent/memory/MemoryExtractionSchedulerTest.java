package com.liche.wechatagent.memory;

import com.liche.wechatagent.user.UserService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionSchedulerTest {

    @Test
    void retriesTransientExtractionFailuresWithoutWaitingForAnotherUserMessage() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            MemoryExtractor extractor = org.mockito.Mockito.mock(MemoryExtractor.class);
            UserService userService = org.mockito.Mockito.mock(UserService.class);
            when(userService.isMemoryEnabled("user-a")).thenReturn(true);
            when(extractor.extract("user-a")).thenReturn(false, true);

            MemoryExtractionScheduler scheduler = new MemoryExtractionScheduler(
                    executor, extractor, userService, 0, 1, 0);
            scheduler.schedule("user-a");

            verify(extractor, timeout(1_000).times(2)).extract("user-a");
        } finally {
            executor.shutdownNow();
        }
    }
}
