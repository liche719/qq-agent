package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体命中排序（2026-10-28）。
 *
 * <p>为什么需要它：用户用**别名**提问（`周二·课`）时，事实里的写法是 `周二课`，
 * 多出的分隔符会把向量相似度拉低，真正该排第一的事实反而沉下去。实体是精确匹配，能补这个短板。
 *
 * <p>这段抽成静态方法就是为了能这样测——用 LLM 跑评估做 A/B 会受"抽取本身不稳定"干扰（实测过）。
 */
class EntityOrderingTest {

    private static MemoryFact fact(String subject, String object) {
        MemoryFact fact = new MemoryFact();
        fact.setSubject(subject);
        fact.setObject(object);
        return fact;
    }

    @Test
    void matchingEntityGoesFirstAndOrderIsStable() {
        MemoryFact other = fact("数学课", "8B304");
        MemoryFact target = fact("周二课", "8B305");

        List<MemoryFact> ordered = MemoryFactService.orderByEntities(
                List.of(other, target), Set.of("周二课"), Function.identity());

        assertEquals("周二课", ordered.get(0).getSubject(), "命中实体的排第一");
        assertEquals("数学课", ordered.get(1).getSubject(), "其余保持原顺序");
    }

    @Test
    void canonicalizerMapsAliasToCanonical() {
        MemoryFact other = fact("别的课", "x");
        MemoryFact target = fact("周二·课", "8B305");

        // 事实里存的是带分隔符的写法，规范写法是"周二课"
        List<MemoryFact> ordered = MemoryFactService.orderByEntities(
                List.of(other, target), Set.of("周二课"), subject -> subject.replace("·", ""));

        assertEquals("周二·课", ordered.get(0).getSubject());
    }

    @Test
    void noEntitiesMeansUntouched() {
        List<MemoryFact> facts = List.of(fact("a", "1"), fact("b", "2"));
        assertSame(facts, MemoryFactService.orderByEntities(facts, Set.of(), Function.identity()));
        assertSame(facts, MemoryFactService.orderByEntities(facts, null, Function.identity()));
    }

    @Test
    void singleOrEmptyIsReturnedAsIs() {
        List<MemoryFact> one = List.of(fact("a", "1"));
        assertSame(one, MemoryFactService.orderByEntities(one, Set.of("a"), Function.identity()));
        assertTrue(MemoryFactService.orderByEntities(List.of(), Set.of("a"), Function.identity()).isEmpty());
    }
}
