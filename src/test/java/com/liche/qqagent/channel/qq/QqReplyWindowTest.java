package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 被动回复窗口（2026-10-27 从 QqChannel 抽出）：过期、淘汰、清空。 */
class QqReplyWindowTest {

    @Test
    void remembersLatestMessagePerConversation() {
        QqReplyWindow window = new QqReplyWindow();
        window.remember("u1", "m1", 1_000L);
        window.remember("u1", "m2", 2_000L);

        assertEquals("m2", window.lastMessageId("u1"));
        assertEquals(2_000L, window.lastReceivedAt("u1"));
        assertEquals(1_000L, window.receivedAt("u1", "m1"));
        assertTrue(window.contains("u1"));
        assertFalse(window.contains("u2"));
    }

    @Test
    void prunesExpiredEntries() {
        QqReplyWindow window = new QqReplyWindow();
        window.remember("u1", "m1", 0L);
        window.prune(10_000L, 5_000L);

        assertNull(window.lastMessageId("u1"));
        assertFalse(window.contains("u1"));
        assertNull(window.receivedAt("u1", "m1"));
    }

    @Test
    void evictsOldestConversationsOnly() {
        QqReplyWindow window = new QqReplyWindow();
        window.remember("old", "m1", 100L);
        window.remember("new", "m2", 200L);

        var evicted = window.evictConversations(1);

        assertEquals(java.util.List.of("old"), evicted);
        assertNull(window.lastMessageId("old"));
        assertEquals("m2", window.lastMessageId("new"));
    }

    @Test
    void clearDropsEverything() {
        QqReplyWindow window = new QqReplyWindow();
        window.remember("u1", "m1", 1L);
        window.clear();
        assertEquals(0, window.conversationCount());
    }
}
