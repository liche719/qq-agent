package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索 RRF（2026-10-28，默认关闭）。
 *
 * <p>它要解决的具体问题：**精确 token（房间号、型号）在纯向量里会被语义稀释**——
 * 六门课六间教室，"8B203 是哪门课"这种反查，向量可能把语义更"像问题"的那条排前面。
 * 词法那一路能把 token 精确对上的顶到前面，RRF 再按名次融合。
 */
class HybridRecallRankerTest {

    private static MemoryFact fact(String subject, String object) {
        MemoryFact fact = new MemoryFact();
        fact.setSubject(subject);
        fact.setObject(object);
        return fact;
    }

    @Test
    void exactTokenMovesToFront() {
        MemoryFact math = fact("高数课", "8B101");
        MemoryFact physics = fact("物理课", "8B203");
        // 向量顺序故意把物理放在最后，模拟"语义相似度没顶上来"
        List<MemoryFact> fused = HybridRecallRanker.fuse(List.of(math, fact("线代课", "8B102"), physics), "8B203 是哪门课");

        assertEquals("物理课", fused.get(0).getSubject(), "精确 token 命中应排第一");
    }

    @Test
    void withoutLexicalHitOrderIsUntouched() {
        List<MemoryFact> facts = List.of(fact("高数课", "8B101"), fact("物理课", "8B203"));
        assertSame(facts, HybridRecallRanker.fuse(facts, "zzzz"), "词法一路完全没命中时不能制造伪信号");
    }

    @Test
    void tokensSplitLatinNumbersAndCjkBigrams() {
        Set<String> tokens = HybridRecallRanker.tokens("8B203 是哪门课");
        assertTrue(tokens.contains("8b203"), "房间号要作为整体 token");
        assertTrue(tokens.contains("门课"), "中文按二元组");
    }

    @Test
    void emptyInputsAreSafe() {
        assertTrue(HybridRecallRanker.fuse(List.of(), "x").isEmpty());
        List<MemoryFact> one = List.of(fact("a", "1"));
        assertSame(one, HybridRecallRanker.fuse(one, "a"));
        assertTrue(HybridRecallRanker.tokens(null).isEmpty());
    }

    @Test
    void rareTokenOutweighsCommonOnes() {
        // 三间教室，"8B203" 只出现在一条里（稀有），"课"出现在三条里（常见）。
        // 没有 IDF 时两者权重相同，精确命中可能顶不过"字面上更像问题"的那条；
        // 有 IDF 之后，稀有 token 的命中必须赢。
        MemoryFact a = fact("高数课", "8B101");
        MemoryFact b = fact("线代课", "8B102");
        MemoryFact c = fact("物理课", "8B203");
        List<MemoryFact> fused = HybridRecallRanker.fuse(List.of(a, b, c), "哪门课在 8B203");
        assertEquals("物理课", fused.get(0).getSubject());
    }
}
