package com.liche.qqagent.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮内注入（2026-10-22）——用户在"笨鱼正在干活"时又发来的消息，塞进这一轮里，而不是另起一轮。
 *
 * <p>用户的原话：<i>「像我在 dsh 里，你在做东西时我强制推送一条消息，你会先完成当前一小阶段
 * 调用的工具，然后再看我发的信息来动态调整后续」</i>。所以：
 * <ul>
 *   <li>一轮在跑时（{@link #enter} 之后），新消息先入队，**不派发新任务**；</li>
 *   <li>{@link AgentLoop} 在每次调用模型之前 {@link #drainTexts} 一次，把新消息作为一条
 *       user 消息追加进上下文——工具照常跑完，模型下一轮就看到了；</li>
 *   <li>如果一条都没赶上（模型已经给出最终答复），队列里剩下的在轮末被
 *       {@link #drainBatches} 取走，合并成下一轮继续处理，不会丢。</li>
 * </ul>
 *
 * <p>上限：一轮最多注入 {@value #MAX_INJECTIONS} 次消息（由 AgentLoop 计数），待注入队列最多
 * {@value #MAX_PENDING_BATCHES} 批——防止消息刷屏把一轮拖成无限循环。
 */
public final class TurnSteering {

    private static final int MAX_PENDING_BATCHES = 3;

    /** 正在跑一轮的用户 */
    private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();
    /** 待注入的消息批（按到达顺序） */
    private static final Map<String, Deque<InboundMessageBatch>> PENDING = new ConcurrentHashMap<>();

    private TurnSteering() {
    }

    public static boolean busy(String userId) {
        return userId != null && BUSY.contains(userId);
    }

    /** 标记"这一轮开始了"；重复调用无副作用 */
    public static void enter(String userId) {
        if (userId != null) {
            BUSY.add(userId);
        }
    }

    /** 这一轮结束（在派发下一轮之前调用） */
    public static void leave(String userId) {
        if (userId != null) {
            BUSY.remove(userId);
        }
    }

    /**
     * 把一条新消息批塞给正在跑的那一轮。
     *
     * @return true = 已入队；false = 队列满（或没有用户），调用方应另起一轮
     */
    public static boolean push(InboundMessageBatch batch) {
        if (batch == null || batch.userId() == null) {
            return false;
        }
        Deque<InboundMessageBatch> queue = PENDING.computeIfAbsent(batch.userId(), ignored -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= MAX_PENDING_BATCHES) {
                return false;
            }
            queue.addLast(batch);
            return true;
        }
    }

    /**
     * 取走待注入的消息（给模型看的文本）。**由 AgentLoop 在每次调模型前调用**。
     *
     * <p>取走即消费：这些消息会被追加到本轮上下文，轮末不会再被当成下一轮。
     */
    public static List<String> drainTexts(String userId) {
        if (userId == null) {
            return List.of();
        }
        Deque<InboundMessageBatch> queue = PENDING.get(userId);
        if (queue == null) {
            return List.of();
        }
        List<InboundMessageBatch> taken;
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            taken = new ArrayList<>(queue);
            queue.clear();
        }
        List<String> texts = new ArrayList<>(taken.size());
        for (InboundMessageBatch batch : taken) {
            String text = batch.historyContent();
            if (text != null && !text.isBlank()) {
                texts.add(text.trim());
            }
        }
        return texts;
    }

    /** 轮末取走没赶上的消息批（调用方合并成下一轮） */
    public static List<InboundMessageBatch> drainBatches(String userId) {
        if (userId == null) {
            return List.of();
        }
        Deque<InboundMessageBatch> queue = PENDING.get(userId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            List<InboundMessageBatch> taken = new ArrayList<>(queue);
            queue.clear();
            return taken;
        }
    }
}
