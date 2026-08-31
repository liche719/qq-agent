package com.liche.wechatagent.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;

/**
 * Bounded shared worker pool with a serial queue per user. A user never has two messages
 * handled concurrently, while inactive queues are reclaimed.
 */
@Component
public class PerUserExecutors {

    private final ConcurrentMap<String, SerialQueue> queues = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService cleanupExecutor;
    private final long idleMillis;
    private final int perUserQueueCapacity;
    private final Semaphore globalQueueSlots;

    @Autowired
    public PerUserExecutors(@Value("${agent.workers:8}") int workerCount,
                            @Value("${agent.queue-capacity:200}") int queueCapacity,
                            @Value("${agent.global-queue-capacity:400}") int globalQueueCapacity,
                            @Value("${agent.user-queue-idle-minutes:30}") long userQueueIdleMinutes) {
        if (workerCount < 1 || queueCapacity < 1 || globalQueueCapacity < 1 || userQueueIdleMinutes < 1) {
            throw new IllegalArgumentException("Agent executor configuration must be positive");
        }
        this.idleMillis = Duration.ofMinutes(userQueueIdleMinutes).toMillis();
        this.perUserQueueCapacity = queueCapacity;
        this.globalQueueSlots = new Semaphore(globalQueueCapacity);
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 30, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), namedFactory("agent-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(namedFactory("agent-queue-cleanup-"));
        this.cleanupExecutor.scheduleAtFixedRate(this::removeIdleQueues, userQueueIdleMinutes,
                userQueueIdleMinutes, TimeUnit.MINUTES);
    }

    PerUserExecutors(int workerCount, int queueCapacity, long userQueueIdleMinutes) {
        this(workerCount, queueCapacity, Math.max(queueCapacity * workerCount * 2, workerCount + 1), userQueueIdleMinutes);
    }

    /** Returns false when this user's bounded queue is full or the service is shutting down. */
    public boolean execute(String userId, Runnable task) {
        if (!globalQueueSlots.tryAcquire()) {
            return false;
        }
        boolean accepted = queues.computeIfAbsent(userId, ignored -> new SerialQueue(workers, perUserQueueCapacity))
                .execute(() -> {
                    try {
                        task.run();
                    } finally {
                        globalQueueSlots.release();
                    }
                });
        if (!accepted) {
            globalQueueSlots.release();
        }
        return accepted;
    }

    public int userCount() {
        return queues.size();
    }

    public int remainingGlobalCapacity() {
        return globalQueueSlots.availablePermits();
    }

    private void removeIdleQueues() {
        long now = System.currentTimeMillis();
        queues.entrySet().removeIf(entry -> entry.getValue().isIdleFor(now, idleMillis));
    }

    @PreDestroy
    void shutdown() {
        cleanupExecutor.shutdownNow();
        workers.shutdownNow();
        queues.clear();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class SerialQueue {
        private final ThreadPoolExecutor workers;
        private final int queueCapacity;
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean running;
        private long lastActivityMillis = System.currentTimeMillis();

        private SerialQueue(ThreadPoolExecutor workers, int queueCapacity) {
            this.workers = workers;
            this.queueCapacity = queueCapacity;
        }

        synchronized boolean execute(Runnable task) {
            if (pending.size() >= queueCapacity) {
                return false;
            }
            pending.add(task);
            lastActivityMillis = System.currentTimeMillis();
            if (running) {
                return true;
            }
            running = true;
            return scheduleNext();
        }

        private boolean scheduleNext() {
            try {
                workers.execute(this::runNext);
                return true;
            } catch (RejectedExecutionException exception) {
                running = false;
                pending.clear();
                return false;
            }
        }

        private void runNext() {
            Runnable next;
            synchronized (this) {
                next = pending.poll();
                if (next == null) {
                    running = false;
                    lastActivityMillis = System.currentTimeMillis();
                    return;
                }
            }
            try {
                next.run();
            } finally {
                synchronized (this) {
                    lastActivityMillis = System.currentTimeMillis();
                    if (pending.isEmpty()) {
                        running = false;
                    } else {
                        scheduleNext();
                    }
                }
            }
        }

        synchronized boolean isIdleFor(long now, long idleMillis) {
            return !running && pending.isEmpty() && now - lastActivityMillis >= idleMillis;
        }
    }
}
