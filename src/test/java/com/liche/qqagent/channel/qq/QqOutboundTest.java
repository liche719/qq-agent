package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发送编排（2026-10-27 从 QqChannel 抽出）：窗口内/外走哪条路、失败后能不能重发。
 * 这几条判错就是"用户收不到"或"收到两遍"，是最需要锁住的行为。
 */
class QqOutboundTest {

    private final List<String> posted = new ArrayList<>();
    private final AtomicBoolean prepared = new AtomicBoolean();
    private final AtomicBoolean tokenEnsured = new AtomicBoolean();
    private final List<String> deleted = new ArrayList<>();
    private int resultUnknown;
    private int attempts;

    private QqOutbound.Deps deps(boolean withinWindow, Exception postFailure, String orphanId) {
        return new QqOutbound.Deps(
                60_000L,
                () -> prepared.set(true),
                () -> tokenEnsured.set(true),
                userId -> "last-msg",
                userId -> withinWindow ? System.currentTimeMillis() : System.currentTimeMillis() - 3_600_000L,
                (userId, msgId) -> withinWindow ? System.currentTimeMillis() : null,
                (userId, text, markdown, replyToMsgId) -> {
                    // 只在第一次调用时失败：模拟"被动那次挂了，重发主动时成功"
                    if (postFailure != null && attempts++ == 0) {
                        throw postFailure;
                    }
                    posted.add(replyToMsgId == null ? "proactive:" + text : "passive:" + text);
                    return "mid-1";
                },
                (userId, messageId) -> {
                },
                userId -> true,
                userId -> {
                },
                () -> "",
                error -> {
                },
                () -> resultUnknown++,
                error -> orphanId == null ? "" : orphanId,
                (userId, messageId) -> deleted.add(messageId));
    }

    @Test
    void sendsPassivelyInsideWindow() {
        QqOutbound outbound = new QqOutbound(deps(true, null, null));
        assertTrue(outbound.sendPassive("u1", "m1", "你好", false));
        assertEquals(List.of("passive:你好"), posted);
        assertTrue(prepared.get());
        assertTrue(tokenEnsured.get());
    }

    @Test
    void fallsBackToProactiveOutsideWindow() {
        QqOutbound outbound = new QqOutbound(deps(false, null, null));
        assertTrue(outbound.sendPassive("u1", null, "在外面", false));
        assertEquals(List.of("proactive:在外面"), posted);
    }

    @Test
    void connectFailureRetriesOnce() {
        // 连接没建起来 = 一个字节都没送到，重发安全
        QqOutbound outbound = new QqOutbound(deps(true, new ConnectException("Connection refused"), null));
        assertTrue(outbound.sendPassive("u1", "m1", "重试", false));
        assertEquals(List.of("proactive:重试"), posted);
        assertEquals(0, resultUnknown);
    }

    @Test
    void resultUnknownDoesNotResend() {
        // 读超时 = 可能已送达：不重发，且被动路径如实返回 true，避免上层再发一遍
        QqOutbound outbound = new QqOutbound(deps(true, new java.net.SocketTimeoutException("Read timed out"), null));
        assertTrue(outbound.sendPassive("u1", "m1", "别重复", false));
        assertEquals(List.of(), posted);
        assertEquals(1, resultUnknown);
    }

    @Test
    void orphanMessageIdIsRecalledBeforeResend() {
        QqOutbound outbound = new QqOutbound(deps(true, new ConnectException("boom"), "orphan-9"));
        outbound.sendPassive("u1", "m1", "撤回再发", false);
        assertEquals(List.of("orphan-9"), deleted);
    }
}
