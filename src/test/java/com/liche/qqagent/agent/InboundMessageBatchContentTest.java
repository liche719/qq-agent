package com.liche.qqagent.agent;

import com.liche.qqagent.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合并批次的文本拼装（2026-10-28 补测）。
 *
 * <p>用户连发几条消息时会被合并成一批送给模型。这里错的表现很具体：
 * 模型只看到最后一条、或者把"最近这条带的图"误当成"每条都带图"。
 */
class InboundMessageBatchContentTest {

    private static InboundMessage msg(String id, String text) {
        return new InboundMessage(id, "u1", text, 0L, "text", "bot", "qq",
                List.of(), List.of(), "", List.of(), List.of());
    }

    @Test
    void singleMessageIsUsedAsIs() {
        InboundMessageBatch batch = InboundMessageBatch.single(msg("m1", "在吗"));

        assertEquals("在吗", batch.content());
        assertEquals("在吗", batch.historyContent());
        assertTrue(batch.hasContent());
    }

    @Test
    void mergedBatchNumbersEachMessage() {
        InboundMessageBatch batch = new InboundMessageBatch(
                List.of(msg("m1", "第一句"), msg("m2", "第二句")), "m2");

        String content = batch.content();

        assertTrue(content.contains("2 条消息"), "合并后要标明条数，否则模型以为只有一句");
        assertTrue(content.contains("[第1条]"));
        assertTrue(content.contains("[第2条]"));
        assertEquals(2, batch.messageIds().size());
    }

    @Test
    void historyTextSaysItWasEarlier() {
        InboundMessageBatch batch = new InboundMessageBatch(
                List.of(msg("m1", "a"), msg("m2", "b")), "m2");

        assertTrue(batch.historyContent().contains("此前"), "历史里要说明是之前发的");
        assertFalse(batch.historyContent().contains("请合并理解"), "历史文本不该带当前回合的指令语气");
    }

    @Test
    void replyAnchorPrefersTheRequestedMessage() {
        InboundMessageBatch batch = new InboundMessageBatch(
                List.of(msg("m1", "a"), msg("m2", "b")), "m1");

        assertEquals("m1", batch.replyAnchor().msgId());
    }

    @Test
    void emptyContentIsDetected() {
        InboundMessageBatch blank = new InboundMessageBatch(List.of(msg("m1", "   ")), "m1");
        assertFalse(blank.hasContent());
    }
}
