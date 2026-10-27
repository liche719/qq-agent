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

/** 追加用例：clipTrimmed（先 trim 再裁，总长正好 max）。 */
class TextsTrimmedTest {

    @org.junit.jupiter.api.Test
    void trimsThenClips() {
        org.junit.jupiter.api.Assertions.assertEquals("abc",
                com.liche.qqagent.support.Texts.clipTrimmed("  abc\n", 10));
        org.junit.jupiter.api.Assertions.assertEquals("abc…",
                com.liche.qqagent.support.Texts.clipTrimmed("abcdefg", 4));
        org.junit.jupiter.api.Assertions.assertEquals("",
                com.liche.qqagent.support.Texts.clipTrimmed(null, 10));
    }
}

/** 追加用例：flatClip（压平 + 总长=max），对应原来 self/ 包两份逐字相同的 brief。 */
class TextsFlatClipTest {

    @org.junit.jupiter.api.Test
    void flattensThenCapsAtMax() {
        org.junit.jupiter.api.Assertions.assertEquals("甲 乙 丙",
                com.liche.qqagent.support.Texts.flatClip("甲\n\n乙\t丙", 200));
        // 200 上限时总长仍是 200（不是 201）
        String longText = "字".repeat(500);
        org.junit.jupiter.api.Assertions.assertEquals(200,
                com.liche.qqagent.support.Texts.flatClip(longText, 200).length());
        org.junit.jupiter.api.Assertions.assertTrue(
                com.liche.qqagent.support.Texts.flatClip(longText, 200).endsWith("…"));
    }

    @org.junit.jupiter.api.Test
    void nullBecomesEmpty() {
        org.junit.jupiter.api.Assertions.assertEquals("",
                com.liche.qqagent.support.Texts.flatClip(null, 200));
    }
}
