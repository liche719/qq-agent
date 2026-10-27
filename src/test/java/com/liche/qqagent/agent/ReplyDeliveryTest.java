package com.liche.qqagent.agent;

import com.liche.qqagent.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 回复交付收尾（2026-10-28 从 AgentOrchestrator 抽出）：语音补发、失败标记。 */
class ReplyDeliveryTest {

    private static InboundMessage message() {
        return new InboundMessage("m1", "u1", "hi", 0L, "text", "bot1", "qq",
                List.of(), List.of(), "", List.of(), List.of());
    }

    @Test
    void flushesVoiceWhenDeferredExists() {
        List<String> sent = new ArrayList<>();
        ReplyDelivery.VoiceSender sender = (botId, userId, audioUrl) -> {
            sent.add(audioUrl);
            return true;
        };

        ReplyDelivery.flushDeferredVoice(() -> "file://a.mp3", ignored -> sender, message(), line -> { });

        assertEquals(List.of("file://a.mp3"), sent);
    }

    @Test
    void doesNothingWhenNoVoicePending() {
        List<String> logs = new ArrayList<>();
        ReplyDelivery.flushDeferredVoice(() -> null, ignored -> fail("不该解析通道"), message(), logs::add);
        assertTrue(logs.isEmpty());
    }

    @Test
    void unknownChannelIsSkipped() {
        ReplyDelivery.flushDeferredVoice(() -> "file://a.mp3", ignored -> null, message(), line -> fail("不该记录"));
    }

    @Test
    void markFailedWritesStateAndLog() {
        List<String> calls = new ArrayList<>();
        ReplyDelivery.TaskMarks marks = new ReplyDelivery.TaskMarks() {
            @Override
            public void fail(String taskId, String userId, String replyTo, String reason) {
                calls.add("fail:" + taskId + ":" + reason);
            }

            @Override
            public void step(String taskId, String step) {
                calls.add("step:" + step);
            }
        };
        List<String> logs = new ArrayList<>();

        ReplyDelivery.markFailed(marks, "t1", "u1", "r1", "send 失败", logs::add);

        assertEquals(List.of("fail:t1:send 失败", "step:REPLY_DELIVERY_FAILED"), calls);
        assertEquals(1, logs.size());
    }

    @Test
    void markFailedToleratesMissingStore() {
        List<String> logs = new ArrayList<>();
        ReplyDelivery.markFailed(null, "t1", "u1", "r1", "原因", logs::add);
        assertEquals(1, logs.size(), "状态存储不可用时仍要记日志");
    }
}
