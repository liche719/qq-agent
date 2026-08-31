package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundMessageBatcherTest {

    @Test
    void combinesConsecutiveMediaAndUsesTheFinalInstructionAsReplyAnchor() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            InboundMessageBatcher batcher = new InboundMessageBatcher(scheduler, 40);
            AtomicReference<InboundMessageBatch> received = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            batcher.submit(InboundMessage.textWithAttachments("file-1", "user-a", "", "qq", "qq", List.of(),
                    List.of(new InboundAttachment("第一份课表.pdf", "application/pdf", "https://example.test/1.pdf"))), batch -> {
                received.set(batch);
                completed.countDown();
            });
            Thread.sleep(10);
            batcher.submit(InboundMessage.text("instruction-2", "user-a", "帮我一起看看这两份资料", "qq", "qq"), batch -> {
                received.set(batch);
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(2, received.get().messages().size());
            assertEquals("instruction-2", received.get().replyToMsgId());
            assertEquals(1, received.get().attachments().size());
            assertTrue(received.get().content().contains("第1条"));
            assertTrue(received.get().content().contains("第2条"));
            batcher.clear();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void keepsSeparateUsersInSeparateBatches() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            InboundMessageBatcher batcher = new InboundMessageBatcher(scheduler, 30);
            CountDownLatch completed = new CountDownLatch(2);
            AtomicReference<InboundMessageBatch> first = new AtomicReference<>();
            AtomicReference<InboundMessageBatch> second = new AtomicReference<>();
            batcher.submit(InboundMessage.textWithImages("a1", "user-a", "", "qq", "qq", List.of("https://example.test/a.jpg")), batch -> {
                first.set(batch);
                completed.countDown();
            });
            batcher.submit(InboundMessage.textWithImages("b1", "user-b", "", "qq", "qq", List.of("https://example.test/b.jpg")), batch -> {
                second.set(batch);
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals("user-a", first.get().userId());
            assertEquals("user-b", second.get().userId());
            assertEquals(1, first.get().messages().size());
            assertEquals(1, second.get().messages().size());
            batcher.clear();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void writesMediaAsHistoricalContextInsteadOfCurrentAttachment() {
        InboundMessageBatch batch = new InboundMessageBatch(List.of(
                InboundMessage.textWithImages("image-1", "user-a", "", "qq", "qq",
                        List.of("https://example.test/schedule.jpg")),
                InboundMessage.text("text-2", "user-a", "这是补充说明", "qq", "qq")), "text-2");

        String history = batch.historyContent();

        assertTrue(history.contains("历史消息曾附带图片"));
        assertTrue(history.contains("图片原件未随当前消息提供"));
        assertTrue(history.contains("这是补充说明"));
    }
}
