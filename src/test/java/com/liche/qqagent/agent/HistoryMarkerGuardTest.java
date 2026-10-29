package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "只有历史媒体标记"的判定（2026-10-27 线上事故的回归测试）。
 *
 * <p>事故：为了不让模型对着 `[历史消息曾附带图片…]` 瞎编，加了"整条只有标记就跳过"的守卫，
 * 但守卫**直接返回空串**——用户发了消息，机器人一句话都没回，而监控全绿（site 200 / 0 ERROR）。
 *
 * <p>现在守卫只负责"判定"，跳过后由调用方**如实回一句**（"这条我只收到一个空的附件标记…"）。
 * 这个类锁住判定本身，避免以后把"标记 + 正常文字"也误判成空消息。
 */
class HistoryMarkerGuardTest {

    @Test
    void markerOnlyIsDetected() {
        assertTrue(AgentLoop.onlyHistoryMarkers("[历史消息曾附带图片；图片原件未随当前消息提供]"));
        assertTrue(AgentLoop.onlyHistoryMarkers("[历史消息曾附带文件；文件原件未随当前消息提供]"));
        assertTrue(AgentLoop.onlyHistoryMarkers(
                "[历史消息曾附带图片；图片原件未随当前消息提供][历史消息曾附带文件；文件原件未随当前消息提供]"));
    }

    @Test
    void markerPlusRealTextIsNotEmpty() {
        // 最容易误伤的情况：合并批次时既带标记、又带用户这轮真正说的话
        assertFalse(AgentLoop.onlyHistoryMarkers(
                "[历史消息曾附带图片；图片原件未随当前消息提供]这个题我不会"));
        assertFalse(AgentLoop.onlyHistoryMarkers("今天几点上课"));
    }

    @Test
    void blankCountsAsEmpty() {
        assertTrue(AgentLoop.onlyHistoryMarkers(null));
        assertTrue(AgentLoop.onlyHistoryMarkers("   "));
    }

    @Test
    void aBareQuestionMarkIsARealMessage() {
        // 2026-10-30 线上：用户只发一个"？"，机器人回"这条我只收到一个空的附件标记"。
        // 一个问号是"你什么意思？"，必须交给模型，不能当成空消息。
        assertFalse(AgentLoop.onlyHistoryMarkers("？"));
        assertFalse(AgentLoop.onlyHistoryMarkers("?"));
        assertFalse(AgentLoop.onlyHistoryMarkers("？？"));
        assertFalse(AgentLoop.onlyHistoryMarkers("没有附件呀"));
    }

    @Test
    void markerPlusQuestionMarkStillHasRealText() {
        assertFalse(AgentLoop.onlyHistoryMarkers(
                "[历史消息曾附带图片；图片原件未随当前消息提供]？"));
    }
}
