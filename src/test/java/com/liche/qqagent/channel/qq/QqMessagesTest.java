package com.liche.qqagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 消息体与会话目标（2026-10-27 从 QqChannel 抽出）：发错会话或漏 msg_seq 都很难在日志里看出来。 */
class QqMessagesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void passiveReplyCarriesMsgIdAndSeq() {
        AtomicInteger seq = new AtomicInteger(3);
        var body = QqMessages.body(json, "hi", false, "msg-1", seq::getAndIncrement);

        assertEquals(0, body.path("msg_type").asInt());
        assertEquals("hi", body.path("content").asText());
        assertEquals("msg-1", body.path("msg_id").asText());
        assertEquals(3, body.path("msg_seq").asInt());
    }

    @Test
    void proactiveMessageHasNoSeq() {
        AtomicInteger seq = new AtomicInteger(1);
        var body = QqMessages.body(json, "**粗体**", true, null, seq::getAndIncrement);

        assertEquals(2, body.path("msg_type").asInt());
        assertEquals("**粗体**", body.path("markdown").path("content").asText());
        assertTrue(body.path("msg_id").isMissingNode());
        assertTrue(body.path("msg_seq").isMissingNode());
    }

    @Test
    void distinguishesGroupFromPrivate() {
        String group = QqMessages.groupConversationId("openid-1");

        assertTrue(group.startsWith("qq-group:"));
        assertTrue(QqMessages.isGroup(group));
        assertFalse(QqMessages.isGroup("openid-1"));
        assertFalse(QqMessages.isGroup(null));
        assertEquals("openid-1", QqMessages.targetOpenid(group));
        assertEquals("openid-1", QqMessages.targetOpenid("openid-1"));
    }
}
