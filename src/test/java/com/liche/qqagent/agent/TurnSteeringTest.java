package com.liche.qqagent.agent;

import com.liche.qqagent.channel.InboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轮内注入（2026-10-28 补测）。
 *
 * <p>用户在机器人干活时插话，这些话会塞进**正在跑的那一轮**，而不是另起一轮。
 * 出错的表现：说了话却没被听见（进了下一轮或丢了），或者消息刷屏把一轮拖成无限循环。
 */
class TurnSteeringTest {

    private static final String USER = "u-test";

    private static InboundMessage message(String text) {
        return new InboundMessage("m-" + text, USER, text, 0L, "text", "bot", "qq",
                List.of(), List.of(), "", List.of(), List.of());
    }

    @AfterEach
    void cleanup() {
        TurnSteering.leave(USER);
        TurnSteering.drainBatches(USER);
        TurnSteering.drainTexts(USER);
    }

    @Test
    void busyTracksTurnLifecycle() {
        assertFalse(TurnSteering.busy(USER));
        TurnSteering.enter(USER);
        assertTrue(TurnSteering.busy(USER));
        TurnSteering.leave(USER);
        assertFalse(TurnSteering.busy(USER), "轮末必须清掉，否则后续消息永远进不了队列");
    }

    @Test
    void drainTextsConsumesOnce() {
        TurnSteering.push(InboundMessageBatch.single(message("停一下")));

        List<String> texts = TurnSteering.drainTexts(USER);
        assertEquals(1, texts.size());
        assertTrue(texts.get(0).contains("停一下"));
        assertTrue(TurnSteering.drainTexts(USER).isEmpty(), "取走即消费，不能重复注入");
    }

    @Test
    void queueHasAnUpperBound() {
        // 上限 3 批：防止刷屏把一轮拖成无限循环
        assertTrue(TurnSteering.push(InboundMessageBatch.single(message("1"))));
        assertTrue(TurnSteering.push(InboundMessageBatch.single(message("2"))));
        assertTrue(TurnSteering.push(InboundMessageBatch.single(message("3"))));
        assertFalse(TurnSteering.push(InboundMessageBatch.single(message("4"))),
                "第 4 批应被拒绝，调用方据此另起一轮");
    }

    @Test
    void drainBatchesTakesWhatDidNotMakeIt() {
        TurnSteering.push(InboundMessageBatch.single(message("没赶上")));

        List<InboundMessageBatch> batches = TurnSteering.drainBatches(USER);

        assertEquals(1, batches.size());
        assertTrue(TurnSteering.drainBatches(USER).isEmpty());
    }

    @Test
    void nullUserIsIgnored() {
        assertFalse(TurnSteering.push(InboundMessageBatch.single(
                new InboundMessage("m", null, "x", 0L, "text", "bot", "qq",
                        List.of(), List.of(), "", List.of(), List.of()))));
        assertTrue(TurnSteering.drainTexts(null).isEmpty());
    }
}
