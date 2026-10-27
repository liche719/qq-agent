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

/** 追加用例：压平空白 与 保留 null 两种语义（2026-10-27 第二批迁移）。 */
class TextsExtraTest {

    @org.junit.jupiter.api.Test
    void clipFlatCollapsesWhitespaceFirst() {
        // 多行原文直接裁会把换行带进日志；这里必须先压成一行
        org.junit.jupiter.api.Assertions.assertEquals("甲 乙 丙",
                com.liche.qqagent.support.Texts.clipFlat("甲\n\n乙\t丙", 20));
        org.junit.jupiter.api.Assertions.assertEquals("甲 乙…",
                com.liche.qqagent.support.Texts.clipFlat("甲  乙  丙丁", 3));
        org.junit.jupiter.api.Assertions.assertEquals("",
                com.liche.qqagent.support.Texts.clipFlat(null, 20));
    }

    @org.junit.jupiter.api.Test
    void clipOrNullKeepsNull() {
        org.junit.jupiter.api.Assertions.assertNull(com.liche.qqagent.support.Texts.clipOrNull(null, 10));
        org.junit.jupiter.api.Assertions.assertEquals("abc",
                com.liche.qqagent.support.Texts.clipOrNull("abc", 10));
        org.junit.jupiter.api.Assertions.assertEquals("abcd…",
                com.liche.qqagent.support.Texts.clipOrNull("abcdefg", 4));
    }
}
