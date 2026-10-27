package com.liche.qqagent.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 数值夹取（2026-10-28 去重：10 个文件各写一份，这里锁住"越界用默认值"而不是"夹到边界"）。 */
class NumbersTest {

    @Test
    void inRangePassesThrough() {
        assertEquals(5, Numbers.bounded(5, 1, 10, 99));
        assertEquals(1, Numbers.bounded(1, 1, 10, 99));
        assertEquals(10, Numbers.bounded(10, 1, 10, 99));
    }

    @Test
    void outOfRangeUsesFallbackNotTheClosestBound() {
        // 关键语义：配置写成 0 或 999 是"写错了"，应回落到默认值，而不是静默夹成 1 / 10
        assertEquals(99, Numbers.bounded(0, 1, 10, 99));
        assertEquals(99, Numbers.bounded(999, 1, 10, 99));
        assertEquals(99, Numbers.bounded(-5, 1, 10, 99));
    }

    @Test
    void supportsLongRange() {
        assertEquals(5L, Numbers.bounded(5L, 1L, 10L, 99L));
        assertEquals(99L, Numbers.bounded(0L, 1L, 10L, 99L));
    }
}
