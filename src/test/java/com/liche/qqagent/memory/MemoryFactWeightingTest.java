package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回加权（2026-10-30）。
 *
 * <p>权重只影响**排序**，不删不改任何记忆——记忆的第一原则是不丢。
 * 权重区间刻意很窄（0.92~1.00），保证"语义更相关"仍然压过"来源更可靠"。
 */
class MemoryFactWeightingTest {

    private static MemoryFact fact(String origin, int daysAgo) {
        MemoryFact fact = new MemoryFact();
        fact.setOrigin(origin);
        fact.setUpdatedAt(LocalDateTime.now().minusDays(daysAgo));
        return fact;
    }

    @Test
    void statedBeatsInferredAtSameSimilarity() {
        double stated = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_STATED, 1), 0.80);
        double inferred = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_INFERRED, 1), 0.80);
        assertTrue(stated > inferred, "他亲口说的应该排在模型推断前面");
    }

    @Test
    void externalIsLowestButStillPositive() {
        double external = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_EXTERNAL, 1), 0.80);
        assertTrue(external > 0);
        assertTrue(external < MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_INFERRED, 1), 0.80));
    }

    @Test
    void recencyOnlySoftlyPrefersNewer() {
        double fresh = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_STATED, 1), 0.80);
        double old = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_STATED, 400), 0.80);
        assertTrue(fresh > old);
        // 窄区间：旧事实最多被削 4%
        assertTrue((fresh - old) / fresh < 0.05, "衰减必须很轻，不能把长期事实削没");
    }

    @Test
    void relevanceStillDominates() {
        // 明显更相关的新事实，必须压过来源更可靠但相关性低的旧事实
        double relevantButInferred = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_INFERRED, 400), 0.90);
        double lessRelevantButStated = MemoryFactService.weightedScore(fact(MemoryFact.ORIGIN_STATED, 1), 0.80);
        assertTrue(relevantButInferred > lessRelevantButStated, "相关性仍然是主导因素");
    }

    @Test
    void nullAndMissingFieldsAreSafe() {
        assertEquals(0d, MemoryFactService.weightedScore(null, 0.9), 1e-9);
        MemoryFact bare = new MemoryFact();
        assertTrue(MemoryFactService.weightedScore(bare, 0.9) > 0);
    }
}
