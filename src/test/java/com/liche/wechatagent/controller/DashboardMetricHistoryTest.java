package com.liche.wechatagent.controller;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardMetricHistoryTest {
    @Test
    void expiresHistoryEvenWithoutNewSamples() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-09-08T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        DashboardMetricHistory history = new DashboardMetricHistory(clock);
        history.record(Map.of("users", 1));
        assertEquals(1, history.snapshot().size());
        when(clock.instant()).thenReturn(start.plusSeconds(3600));
        assertTrue(history.snapshot().isEmpty());
    }

    @Test
    void boundsCapacityAndCopiesNestedValues() {
        DashboardMetricHistory history = new DashboardMetricHistory();
        Map<String, Object> jvm = new HashMap<>();
        jvm.put("heapUsed", 123);
        for (int index = 0; index < 4000; index++) history.record(Map.of("jvm", jvm));
        jvm.put("heapUsed", 999);
        assertEquals(3600, history.snapshot().size());
        assertEquals(123, ((Map<?, ?>) history.snapshot().getFirst().get("jvm")).get("heapUsed"));
        assertTrue(new DashboardMetricHistory().snapshot().isEmpty());
    }
}
