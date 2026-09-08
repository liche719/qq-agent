package com.liche.wechatagent.controller;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@Component
public class DashboardMetricHistory {
    private static final Duration RETENTION = Duration.ofHours(1);
    private static final int CAPACITY = 3600;
    private final Clock clock;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public DashboardMetricHistory() {
        this(Clock.systemUTC());
    }

    DashboardMetricHistory(Clock clock) {
        this.clock = clock;
    }

    public synchronized void record(Map<String, Object> overview) {
        Instant now = clock.instant();
        prune(now);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("at", now.toString());
        for (String key : List.of("qq", "users", "tasks", "jvm")) {
            Object value = overview.get(key);
            values.put(key, value instanceof Map<?, ?> nested
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(nested)) : value);
        }
        samples.addLast(new Sample(now, Collections.unmodifiableMap(values)));
        while (samples.size() > CAPACITY) samples.removeFirst();
    }

    public synchronized List<Map<String, Object>> snapshot() {
        prune(clock.instant());
        return samples.stream().map(Sample::values).toList();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        while (!samples.isEmpty() && !samples.getFirst().at().isAfter(cutoff)) {
            samples.removeFirst();
        }
    }

    private record Sample(Instant at, Map<String, Object> values) {}
}
