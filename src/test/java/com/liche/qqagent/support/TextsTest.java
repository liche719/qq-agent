package com.liche.qqagent.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 文本裁剪（2026-10-27 统一入口）：两种语义必须分清，迁移时混用会改变落库/日志长度。 */
class TextsTest {

    @Test
    void clipKeepsTotalLengthAtMax() {
        assertEquals("abc", Texts.clip("abc", 5));
        assertEquals("abcd…", Texts.clip("abcdefg", 5));   // 4 + 1 = 5
        assertEquals("", Texts.clip(null, 5));
    }

    @Test
    void clipWideAllowsOneExtraCharForEllipsis() {
        assertEquals("abc", Texts.clipWide("abc", 5));
        assertEquals("abcde…", Texts.clipWide("abcdefg", 5)); // 5 + 1 = 6
        assertEquals("", Texts.clipWide(null, 5));
    }

    @Test
    void nonPositiveMaxDegradesSafely() {
        assertEquals("", Texts.clip("abc", 0));
        assertEquals("…", Texts.clipWide("abc", 0));
    }
}
