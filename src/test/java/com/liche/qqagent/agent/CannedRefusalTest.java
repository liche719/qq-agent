package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板化拒答识别（2026-10-27）。
 *
 * <p>这几条**全部来自线上真实事故**：用户问"还有9天能学完 agent 吗"，模型回了
 * "抱歉，我无法回答这个问题。"，随后另一句换成"你好，我无法给到相关内容。"——
 * 第二种措辞一开始没被识别，所以这里把两种都锁住。
 */
class CannedRefusalTest {

    @Test
    void catchesRealRefusalsFromProduction() {
        assertTrue(AgentLoop.looksLikeCannedRefusal("你好，这个问题我无法回答，很遗憾不能帮助你。"));
        assertTrue(AgentLoop.looksLikeCannedRefusal("你好，我无法给到相关内容。"));
        assertTrue(AgentLoop.looksLikeCannedRefusal("抱歉，我无法回答这个问题。"));
        assertTrue(AgentLoop.looksLikeCannedRefusal("I can't help with that."));
    }

    @Test
    void ignoresNormalAnswers() {
        assertFalse(AgentLoop.looksLikeCannedRefusal("9 天学不完，但你不需要学完——你要的是能讲清楚那三段。"));
        assertFalse(AgentLoop.looksLikeCannedRefusal(""));
        assertFalse(AgentLoop.looksLikeCannedRefusal(null));
    }

    @Test
    void ignoresLongAnswersThatMentionRefusal() {
        // 长回答里出现"无法回答"是正常表达（比如解释某个问题为什么没法回答），不能误判
        String longAnswer = "这个问题确实无法回答，因为" + "前提没有给清楚。".repeat(20);
        assertFalse(AgentLoop.looksLikeCannedRefusal(longAnswer));
    }
}
