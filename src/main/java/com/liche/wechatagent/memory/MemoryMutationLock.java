package com.liche.wechatagent.memory;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes memory mutations for one user so an in-flight extractor cannot restore forgotten data. */
@Component
public class MemoryMutationLock {

    private static final int STRIPE_COUNT = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public MemoryMutationLock() {
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    public void runExclusive(String userId, Runnable action) {
        callExclusive(userId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callExclusive(String userId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        ReentrantLock lock = locks[Math.floorMod(Objects.hashCode(userId), locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
