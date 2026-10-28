package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 前缀指纹（2026-10-28 诊断用）：同一内容必须同指纹，内容一变指纹就得变，否则诊断没有意义。 */
class PrefixFingerprintTest {

    @Test
    void sameTextGivesSameHash() {
        assertEquals(AgentLoop.shortHash("abc"), AgentLoop.shortHash("abc"));
        assertEquals(8, AgentLoop.shortHash("abc").length());
    }

    @Test
    void differentTextGivesDifferentHash() {
        assertNotEquals(AgentLoop.shortHash("abc"), AgentLoop.shortHash("abd"));
        assertNotEquals(AgentLoop.shortHash(""), AgentLoop.shortHash(" "));
    }

    @Test
    void nullIsTreatedAsEmpty() {
        assertEquals(AgentLoop.shortHash(""), AgentLoop.shortHash(null));
    }
}
