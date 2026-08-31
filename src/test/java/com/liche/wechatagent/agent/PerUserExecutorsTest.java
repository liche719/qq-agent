package com.liche.wechatagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerUserExecutorsTest {

    @Test
    void keepsOneUsersMessagesOrderedAndBoundsTheirBacklog() throws Exception {
        PerUserExecutors executors = new PerUserExecutors(2, 2, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        List<Integer> order = new CopyOnWriteArrayList<>();
        try {
            assertTrue(executors.execute("user-a", () -> {
                firstStarted.countDown();
                await(releaseFirst);
                order.add(1);
                completed.countDown();
            }));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertTrue(executors.execute("user-a", () -> {
                order.add(2);
                completed.countDown();
            }));
            assertTrue(executors.execute("user-a", () -> {
                order.add(3);
                completed.countDown();
            }));
            assertFalse(executors.execute("user-a", () -> { }));

            releaseFirst.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), order);
        } finally {
            executors.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
