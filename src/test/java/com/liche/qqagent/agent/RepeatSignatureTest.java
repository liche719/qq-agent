package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同参重复检测的辅助逻辑（2026-10-29）。
 *
 * <p>规范化必须"把空白差异抹平"：模型有时把
 * {@code {"query":"a","count":6}} 和 {@code {"query": "a", "count": 6}} 当成两次不同调用，
 * 若按原串比较就检测不到原地打转。
 */
class RepeatSignatureTest {

    @Test
    void whitespaceDifferencesAreIgnored() {
        assertEquals(AgentLoop.normalizeArguments("{\"query\": \"a\", \"count\": 6}"),
                AgentLoop.normalizeArguments("{\"query\":\"a\",\"count\":6}"));
    }

    @Test
    void differentValuesStayDifferent() {
        assertNotEquals(AgentLoop.normalizeArguments("{\"query\":\"a\"}"),
                AgentLoop.normalizeArguments("{\"query\":\"b\"}"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", AgentLoop.normalizeArguments(null));
    }
}
