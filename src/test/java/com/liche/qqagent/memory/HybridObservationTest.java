package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 观测用的辅助逻辑（2026-10-28）：分词与"缩写"必须稳定，否则日志会骗人。 */
class HybridObservationTest {

    @Test
    void tokensSeparateIdsFromChinese() {
        var tokens = HybridRecallRanker.tokens("8B304 是哪门课");
        assertTrue(tokens.contains("8b304"));
        assertTrue(tokens.contains("门课"));
        assertFalse(tokens.contains("8b30"), "数字字母要整段，不能切成前缀");
    }

    @Test
    void tokensHandleBlank() {
        assertTrue(HybridRecallRanker.tokens(null).isEmpty());
        assertTrue(HybridRecallRanker.tokens("   ").isEmpty());
    }

    @Test
    void fuseKeepsLength() {
        MemoryFact a = new MemoryFact();
        a.setSubject("数学课");
        a.setObject("8B304");
        MemoryFact b = new MemoryFact();
        b.setSubject("英语课");
        b.setObject("3C201");
        List<MemoryFact> fused = HybridRecallRanker.fuse(List.of(a, b), "8B304");
        assertEquals(2, fused.size());
        assertEquals("数学课", fused.get(0).getSubject());
    }
}
