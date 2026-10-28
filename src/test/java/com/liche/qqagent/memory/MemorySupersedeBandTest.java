package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 相似带（2026-10-28）。
 *
 * <p>三档行为必须能区分：
 * <ul>
 *   <li>≥ 阈值：同一条记忆（合并，不新增）；</li>
 *   <li>[floor, 阈值)：同一件事的旧版本 → 旧条目标 SUPERSEDED、新条目插入；</li>
 *   <li>&lt; floor：无关。</li>
 * </ul>
 * 判错的后果很具体：把"计划→已完成"当成同一个（丢历史），或者把两件不同的事标的失效（丢记忆）。
 */
class MemorySupersedeBandTest {

    /** 默认阈值 0.75、下限 0.65 */
    private MemoryContentSimilarity similarity() {
        return new MemoryContentSimilarity(0.75d);
    }

    @Test
    void sameSentenceIsDuplicate() {
        assertTrue(similarity().isDuplicate("用户是计算机的学生", "用户是计算机的学生。"));
    }

    @Test
    void thresholdChangeActuallyCatchesRealDuplicates() {
        // 这对来自生产库（196 条里 Jaccard 0.66 的那类近重复）。实测相似度 0.774：
        //   - 旧阈值 0.8 → 不算重复 → 两条并存，白占注入名额（这就是要修的问题）
        //   - 新阈值 0.75 → 算重复 → 合并
        String a = "用户明早先复印两份重修申请表，12节课下课后去找陈立中老师签字";
        String b = "用户计划明早复印两份重修申请表，12节课下课后10:10去找陈立中老师签申请表";
        double score = similarity().score(a, b);
        assertTrue(score >= 0.75 && score < 0.8,
                "这对改写应刚好落在 0.75~0.8 之间（实际 " + score + "），才说明调阈值有意义");
        assertTrue(similarity().isDuplicate(a, b), "新阈值下应判为重复");
    }

    @Test
    void similarButDifferentFallsIntoTheBand() {
        // 下限 0.65：比"重复"松一档，用来识别"同一件事的旧版本"
        String a = "用户计划明早复印两份重修申请表并找陈立中老师签字";
        String b = "用户已经复印好重修申请表，等陈立中老师签字";
        double score = similarity().score(a, b);
        assertTrue(score < 0.75 && score >= 0.4,
                "这对应是'相似但不同'（实际 " + score + "）");
        assertFalse(similarity().isDuplicate(a, b));
    }

    @Test
    void oppositePolarityIsNeverSimilar() {
        // "不再学英语" 与 "在学英语" 不能因为字面像就被当成同一件事
        assertEquals(0d, similarity().score("用户不再学英语了", "用户一直在学英语"), 1e-9);
        assertFalse(similarity().isDuplicate("用户不再学英语了", "用户一直在学英语"));
    }

    @Test
    void unrelatedContentIsBelowFloor() {
        double score = similarity().score("用户喜欢喝美式咖啡", "周三的体育课在体育馆上");
        assertTrue(score < 0.65, "无关内容不该触发替代（实际 " + score + "）");
    }
}
