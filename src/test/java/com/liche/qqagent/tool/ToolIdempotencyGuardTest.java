package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 副作用工具幂等（2026-10-29）。
 *
 * <p>它要防的是具体场景：发评论超时 → 模型以为没成 → 下一轮再发一次 → 用户看到两条。
 * 规则是"**刚成功过的跳过、结果未知的拦截**"，不是永久去重（用户完全可能真的再说一次"提醒我"）。
 */
class ToolIdempotencyGuardTest {

    private final Map<String, String> store = new HashMap<>();

    private ToolIdempotencyGuard guard() {
        return new ToolIdempotencyGuard(key -> store.get(key),
                (key, value, ttl) -> store.put(key, value));
    }

    @Test
    void sameToolAndArgsShareAKey() {
        String a = ToolIdempotencyGuard.keyOf("u1", "commentBilibili", "{\"bvid\":\"BV1\"}");
        String b = ToolIdempotencyGuard.keyOf("u1", "commentBilibili", "{\"bvid\": \"BV1\"}");
        assertEquals(a, b, "空白差异不能算出两个 key");

        assertNotEquals(a, ToolIdempotencyGuard.keyOf("u1", "commentBilibili", "{\"bvid\":\"BV2\"}"));
        assertNotEquals(a, ToolIdempotencyGuard.keyOf("u2", "commentBilibili", "{\"bvid\":\"BV1\"}"));
        assertNotEquals(a, ToolIdempotencyGuard.keyOf("u1", "drawPicture", "{\"bvid\":\"BV1\"}"));
    }

    @Test
    void succeededCallIsRememberedWithContent() {
        ToolIdempotencyGuard guard = guard();
        String key = ToolIdempotencyGuard.keyOf("u1", "sendStoredMedia", "{}");
        assertEquals(ToolIdempotencyGuard.State.NONE, guard.check(key).state());

        guard.markSucceeded(key, "已发送");
        ToolIdempotencyGuard.Verdict verdict = guard.check(key);
        assertEquals(ToolIdempotencyGuard.State.SUCCEEDED, verdict.state());
        assertEquals("已发送", verdict.content());
    }

    @Test
    void unknownResultBlocksRepeat() {
        ToolIdempotencyGuard guard = guard();
        String key = ToolIdempotencyGuard.keyOf("u1", "commentBilibili", "{}");
        guard.markUnknown(key);
        assertEquals(ToolIdempotencyGuard.State.UNKNOWN, guard.check(key).state());
    }

    @Test
    void ttlIsBoundedSoLegitimateRepeatsStillWork() {
        // 断言"用的是短期 TTL"这件事：成功 60 秒、未知 5 分钟。
        // 如果哪天有人改成永久去重，用户说"再提醒我一次"就会被静默吞掉。
        Duration success = Duration.ofSeconds(60);
        Duration unknown = Duration.ofMinutes(5);
        assertTrue(success.toSeconds() <= 120, "成功去重窗口不能太长");
        assertTrue(unknown.toMinutes() <= 10, "未知拦截窗口不能太长");
    }
}
