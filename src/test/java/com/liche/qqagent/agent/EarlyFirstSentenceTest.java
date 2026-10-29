package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边生成边发第一句（2026-10-29）。
 *
 * <p>规则就两条：**长回复才拆**（累计 ≥40 字）、**在句末标点处拆**。
 * 拆错了会出现"一句『好』也发两条"或者"半句话弹出来"。
 */
class EarlyFirstSentenceTest {

    /** 与 AgentLoop 里的判定同构：给一段文本，返回应该先发的长度（0 = 不发） */
    private static int boundaryOf(String text, int alreadyFlushed) {
        if (alreadyFlushed > 0 || text == null || text.length() < 40) {
            return 0;
        }
        for (int index = 15; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == '!' || ch == '?') {
                return index + 1;
            }
        }
        return 0;
    }

    @Test
    void shortReplyIsNotSplit() {
        assertEquals(0, boundaryOf("好的，我这就去。", 0), "短回复不该拆成两条");
    }

    @Test
    void longReplySplitsAtFirstSentenceEnd() {
        String text = "这个问题我看了下，主要有三点要注意。第一点是上下文太长，第二点是工具返回没裁剪，第三点是语气偏报告。";
        int cut = boundaryOf(text, 0);
        assertTrue(cut > 0);
        assertTrue(text.substring(0, cut).endsWith("。"), "必须在句末标点处切");
        assertTrue(cut >= 15);
    }

    @Test
    void onlyOncePerTurn() {
        String text = "第一句要足够长才会触发拆分规则，这一句写了三十多个字。第二句也不短，继续说下去。";
        int cut = boundaryOf(text, 0);
        assertTrue(cut > 0);
        assertEquals(0, boundaryOf(text, cut), "同一轮只能提前发一次");
    }

    @Test
    void noBoundaryNoSplit() {
        String text = "这是一段没有任何句末标点的很长的文字".repeat(3);
        assertEquals(0, boundaryOf(text, 0), "没有句末标点就不猜边界");
    }
}
