package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 进度条会话（2026-10-27 从 QqChannel 抽出）：12 行封顶、平台拒收后仍继续攒。 */
class QqProgressSessionsTest {

    @Test
    void appendsLinesWithBulletPrefix() {
        QqProgressSessions sessions = new QqProgressSessions();
        var session = sessions.computeIfAbsent("u1", () -> 7);

        assertTrue(sessions.appendLine(session, "看B站视频：xxx"));
        assertTrue(sessions.appendLine(session, "搜索：yyy"));

        assertEquals("· 看B站视频：xxx\n· 搜索：yyy", session.text.toString());
        assertEquals(7, session.msgSeq);
    }

    @Test
    void capsAtTwelveLines() {
        QqProgressSessions sessions = new QqProgressSessions();
        var session = sessions.computeIfAbsent("u1", () -> 1);

        for (int i = 1; i <= 12; i++) {
            assertTrue(sessions.appendLine(session, "第" + i + "行"), "第 " + i + " 行应该能加");
        }
        assertFalse(sessions.appendLine(session, "第13行"), "第 13 行应该被上限挡住");
        assertEquals(12, session.text.toString().split("\n").length);
    }

    @Test
    void deadSessionKeepsAccumulatingButDoesNotSend() {
        QqProgressSessions sessions = new QqProgressSessions();
        var session = sessions.computeIfAbsent("u1", () -> 1);
        sessions.appendLine(session, "第一行");
        session.dead = true;

        assertFalse(sessions.appendLine(session, "第二行"), "死了之后不再发帧");
        assertTrue(session.text.toString().contains("第二行"), "但仍要攒起来，收尾补发");
    }

    @Test
    void removeReturnsAndDropsSession() {
        QqProgressSessions sessions = new QqProgressSessions();
        sessions.computeIfAbsent("u1", () -> 1);

        assertNotNull(sessions.remove("u1"));
        assertNull(sessions.remove("u1"));
        assertNull(sessions.get("u1"));
    }
}
