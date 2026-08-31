package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMutationLockTest {

    @Test
    void serializesConcurrentMutationsForTheSameUser() throws Exception {
        MemoryMutationLock lock = new MemoryMutationLock();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            secondStarted.countDown();
            lock.runExclusive("u1", secondFinished::countDown);
        });

        lock.runExclusive("u1", () -> {
            second.start();
            try {
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
                assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
    }
}
