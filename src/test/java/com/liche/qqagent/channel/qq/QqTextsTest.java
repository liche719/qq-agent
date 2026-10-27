package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** markdown 判据（2026-10-27 从 QqChannel 抽出）：判错会让排版变成一堆星号，或者该渲染的不渲染。 */
class QqTextsTest {

    @Test
    void detectsStructuredText() {
        assertTrue(QqTexts.markdownLike("# 标题\n- 第一条\n- 第二条", 1000));
        assertTrue(QqTexts.markdownLike("1. 先做 A\n2. 再做 B", 1000));
        assertTrue(QqTexts.markdownLike("**重点** 在这里\n还有 `代码`", 1000));
    }

    @Test
    void plainChatStaysPlain() {
        assertFalse(QqTexts.markdownLike("在吗", 1000));
        assertFalse(QqTexts.markdownLike("今天天气不错，出去走走", 1000));
        assertFalse(QqTexts.markdownLike(null, 1000));
        assertFalse(QqTexts.markdownLike("   ", 1000));
    }

    @Test
    void tooLongFallsBackToPlainText() {
        String longText = "# 标题\n" + "- 条目\n".repeat(400);
        assertFalse(QqTexts.markdownLike(longText, 500));
        assertTrue(QqTexts.markdownLike(longText, 0)); // 0 = 不限长
    }

    @Test
    void toolFooterAlwaysMarkdown() {
        assertTrue(QqTexts.markdownLike("好的\n> _调用工具：搜索_", 1000));
    }
}
