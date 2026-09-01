package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContentSimilarityTest {

    private final MemoryContentSimilarity similarity = new MemoryContentSimilarity(0.8d);

    @Test
    void recognizesEquivalentLongTermGoalDespiteMinorWordingDifferences() {
        assertTrue(similarity.isDuplicate("用户的长期目标是考取南京理工大学研究生",
                "用户长期目标是考南京理工大学研究生"));
    }

    @Test
    void doesNotCollapseOppositePreferences() {
        assertFalse(similarity.isDuplicate("我喜欢摄影和风景照片", "我不喜欢摄影和风景照片"));
    }

    @Test
    void keepsUnrelatedFactsSeparate() {
        assertFalse(similarity.isDuplicate("用户正在准备考研", "用户喜欢喝奶茶"));
    }

    @Test
    void fallsBackToTheSafeDefaultForInvalidThresholds() {
        MemoryContentSimilarity invalid = new MemoryContentSimilarity(Double.NaN);

        assertTrue(invalid.isDuplicate("用户的长期目标是完成研究计划", "用户长期目标是完成研究计划"));
    }
}
