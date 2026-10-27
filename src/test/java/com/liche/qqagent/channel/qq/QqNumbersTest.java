package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 数值夹取与退避解析（2026-10-27 从 QqChannel 抽出）：边界处理，最值得测。 */
class QqNumbersTest {

    @Test
    void boundedFallsBackOutsideRange() {
        assertEquals(5L, QqNumbers.bounded(5L, 1L, 10L, 99L));
        assertEquals(99L, QqNumbers.bounded(0L, 1L, 10L, 99L));
        assertEquals(99L, QqNumbers.bounded(11L, 1L, 10L, 99L));
        assertEquals(3, QqNumbers.bounded(3, 1, 10, 99));
        assertEquals(99, QqNumbers.bounded(0, 1, 10, 99));
    }

    @Test
    void backoffFallsBackToDefaults() {
        long[] defaults = {2_000L, 5_000L, 10_000L, 30_000L};
        assertArrayEquals(defaults, QqNumbers.parseBackoff(null));
        assertArrayEquals(defaults, QqNumbers.parseBackoff("  "));
        assertArrayEquals(defaults, QqNumbers.parseBackoff("abc,def"));
        // 全部越界（<250 或 >600000）也回默认
        assertArrayEquals(defaults, QqNumbers.parseBackoff("1,99999999"));
    }

    @Test
    void backoffKeepsValidEntriesOnly() {
        assertArrayEquals(new long[]{1_000L, 20_000L}, QqNumbers.parseBackoff("1000, 20, 20000"));
    }
}
