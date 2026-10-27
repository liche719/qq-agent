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

/** 长消息切分：切错会导致页数爆炸或长行被硬切断语义。 */
class QqTextsSplitTest {

    @org.junit.jupiter.api.Test
    void splitsAtLineBreaksWhenPossible() {
        String text = "第一段内容\n".repeat(10);
        var parts = QqTexts.splitForSending(text, 40);
        org.junit.jupiter.api.Assertions.assertTrue(parts.size() > 1);
        // 每页都不该包含下一段的开头（说明是在换行处断的）
        for (String part : parts) {
            org.junit.jupiter.api.Assertions.assertFalse(part.startsWith("\n"));
            org.junit.jupiter.api.Assertions.assertTrue(part.length() <= 40);
        }
    }

    @org.junit.jupiter.api.Test
    void shortTextStaysOnePage() {
        var parts = QqTexts.splitForSending("就一句话", 100);
        org.junit.jupiter.api.Assertions.assertEquals(1, parts.size());
    }

    @org.junit.jupiter.api.Test
    void blankInputProducesNoPages() {
        org.junit.jupiter.api.Assertions.assertTrue(QqTexts.splitForSending("", 100).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(QqTexts.splitForSending(null, 100).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void labelsPagesWithNumbers() {
        var labeled = QqTexts.labelPages(java.util.List.of("a", "b"));
        org.junit.jupiter.api.Assertions.assertEquals("[1/2]\na", labeled.get(0));
        org.junit.jupiter.api.Assertions.assertEquals("[2/2]\nb", labeled.get(1));
    }
}

/** 上传文件类型判定（2026-10-27 从 QqChannel 抽出）：判错会让图片/语音发错通道。 */
class QqTextsFileTypeTest {

    @org.junit.jupiter.api.Test
    void mapsKnownTypes() {
        org.junit.jupiter.api.Assertions.assertEquals(1, QqTexts.fileType("image/png"));
        org.junit.jupiter.api.Assertions.assertEquals(1, QqTexts.fileType("IMAGE/JPEG"));
        org.junit.jupiter.api.Assertions.assertEquals(2, QqTexts.fileType("video/mp4"));
        org.junit.jupiter.api.Assertions.assertEquals(3, QqTexts.fileType("audio/amr"));
        org.junit.jupiter.api.Assertions.assertEquals(3, QqTexts.fileType("application/silk"));
    }

    @org.junit.jupiter.api.Test
    void fallsBackToOther() {
        org.junit.jupiter.api.Assertions.assertEquals(4, QqTexts.fileType(null));
        org.junit.jupiter.api.Assertions.assertEquals(4, QqTexts.fileType("application/pdf"));
        org.junit.jupiter.api.Assertions.assertEquals(4, QqTexts.fileType("image/gif"));
    }
}
