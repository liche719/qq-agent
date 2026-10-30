package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩前"记忆抢救"的取数逻辑（2026-10-30）。
 *
 * <p>要解决的问题：长对话压缩会把最早几轮**原文丢掉**只留摘要，那几轮里值得入库的事实
 * 就永远看不到了。这里锁住"从被丢掉的历史里挑出用户原话"这一步——
 * 挑错（比如把 assistant 的话也塞进去）会让提取器把模型自己编的内容当成用户说过的话。
 */
class MemoryRescueBeforeCompactTest {

    @Test
    void picksOnlyUserTexts() {
        List<ContextTurn> dropped = List.of(
                new ContextTurn("user", "我下周改考自命题了"),
                new ContextTurn("assistant", "好，记下了"),
                new ContextTurn("user", "周三的课改到下午三点"));
        assertEquals(List.of("我下周改考自命题了", "周三的课改到下午三点"),
                AgentOrchestrator.rescueTexts(dropped));
    }

    @Test
    void skipsBlankAndNulls() {
        List<ContextTurn> dropped = List.of(
                new ContextTurn("user", "  "),
                new ContextTurn("user", null),
                new ContextTurn("user", "  有内容的一句  "));
        assertEquals(List.of("  有内容的一句  "), AgentOrchestrator.rescueTexts(dropped));
    }

    @Test
    void emptyInputIsSafe() {
        assertTrue(AgentOrchestrator.rescueTexts(null).isEmpty());
        assertTrue(AgentOrchestrator.rescueTexts(List.of()).isEmpty());
        assertTrue(AgentOrchestrator.rescueTexts(List.of(new ContextTurn("assistant", "只有模型的话"))).isEmpty());
    }
}
