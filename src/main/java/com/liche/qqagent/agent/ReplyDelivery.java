package com.liche.qqagent.agent;

import com.liche.qqagent.channel.InboundMessage;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 回复的"交付收尾"（2026-10-28 从 {@code AgentOrchestrator} 拆出，第 8 步）。
 *
 * <p>这里只管两件发生在**回复之后**的事：
 *
 * <ul>
 *   <li>{@link #flushDeferredVoice}：语音被模型排到"文字之后"时，正文发完再补发语音；</li>
 *   <li>{@link #markFailed}：回复没发出去时，把任务状态标失败，否则面板上会永远显示"进行中"。</li>
 * </ul>
 *
 * <p>两者都只有十几行，但都是"出错时最难发现"的地方：语音不补发，用户只是觉得"它没说话"；
 * 状态不标失败，运维看不出有任务卡住。抽出来是为了能单独读、单独测。
 */
final class ReplyDelivery {

    /** 任务状态标记（正常由 {@code AgentTaskStateStore} 实现） */
    interface TaskMarks {
        void fail(String taskId, String userId, String replyToMessageId, String reason);

        void step(String taskId, String step);
    }

    /** 发语音（正常由 channel 实现） */
    interface VoiceSender {
        boolean send(String botId, String userId, String audioUrl);
    }

    private ReplyDelivery() {
    }

    /**
     * 补发被推迟的语音。
     *
     * @param drain      取出待发语音（没有就返回 null）
     * @param channelFor 按通道名解析发送器（解析不到就跳过，不能因此抛异常打断回复）
     */
    static void flushDeferredVoice(Supplier<String> drain, Function<String, VoiceSender> senderFor,
                                   InboundMessage msg, java.util.function.Consumer<String> logSent) {
        if (msg == null) {
            return;
        }
        String audioUrl = drain == null ? null : drain.get();
        if (audioUrl == null) {
            return;
        }
        VoiceSender sender = senderFor == null ? null : senderFor.apply(msg.channel());
        if (sender == null) {
            return;
        }
        boolean sent = sender.send(msg.botId(), msg.userId(), audioUrl);
        if (logSent != null) {
            logSent.accept("同步任务补发语音 user=" + msg.userId() + " sent=" + sent);
        }
    }

    /**
     * 回复交付失败：标任务失败并记一条 error（面板据此显示异常，不再永远"进行中"）。
     *
     * @param marks 可为 null（未启用任务状态存储时）
     */
    static void markFailed(TaskMarks marks, String taskId, String userId, String replyToMessageId,
                           String reason, java.util.function.Consumer<String> logError) {
        if (marks != null) {
            marks.fail(taskId, userId, replyToMessageId, reason);
            marks.step(taskId, "REPLY_DELIVERY_FAILED");
        }
        if (logError != null) {
            logError.accept("agent_reply_delivery_failed task=" + taskId + " user=" + userId
                    + " replyTo=" + replyToMessageId + " reason=" + reason);
        }
    }
}
