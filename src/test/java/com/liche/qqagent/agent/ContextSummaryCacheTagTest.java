package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文摘要的缓存键（2026-10-28 补测 + 疑点记录）。
 *
 * <p>规则（当前实现）：**优先用被截掉的那段里"第一条消息"的 id**；没有 id 时用所有文本的哈希。
 *
 * <p>⚠️ 这里有个待验证的疑点（不臆断为 bug）：摘要缓存按"最早那条消息"作 key，
 * 而 `dropped` 每次都是"最近 3N 轮里除去最近 N 轮"的滑动窗口——
 * 如果窗口起点会随新消息前移，那么 key 每次都在变，**缓存几乎不命中**（每轮都重新摘要、多花钱）；
 * 如果起点不变，那么 key 不变而内容在变，**摘要会过期**。
 * 两种情况都需要真实流量才能确认（看 Redis 里 `ctx:summary:*` 的 key 数量与命中情况）。
 * 现在把当前行为锁住，改之前先想清楚要往哪个方向修。
 */
class ContextSummaryCacheTagTest {

    private static ContextTurn turn(String text, List<String> ids) {
        return new ContextTurn(text, text, ids);
    }

    private ContextSummaryService service() {
        // 只测纯函数 cacheTag，其它依赖传 null
        return new ContextSummaryService(null, null, false, 6, 12);
    }

    @Test
    void prefersTheFirstMessageId() {
        assertEquals("m-1", service().cacheTag(List.of(
                turn("a", List.of("m-1")), turn("b", List.of("m-2")))));
    }

    @Test
    void sameInputGivesSameTag() {
        var svc = service();
        assertEquals(svc.cacheTag(List.of(turn("a", null), turn("b", null))),
                svc.cacheTag(List.of(turn("a", null), turn("b", null))));
    }

    @Test
    void differentTextGivesDifferentTag() {
        var svc = service();
        assertNotEquals(svc.cacheTag(List.of(turn("a", null))),
                svc.cacheTag(List.of(turn("b", null))));
    }

    @Test
    void fallsBackToHashWhenNoIds() {
        String tag = service().cacheTag(List.of(turn("没有 id 的内容", List.of())));
        assertNotNull(tag);
        assertFalse(tag.isBlank());
    }
}
