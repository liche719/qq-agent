package com.liche.wechatagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一轮对话里的**进度播报**（2026-10-20 加）。
 *
 * <p>用户的原话：「笨鱼不一定每次只回复我一条／一段话，可以分开回复，而且在长任务场景下很实用
 * ——先回复我一句，然后再去找，甚至找的半路途中也可以给我发消息／汇报进度」。
 *
 * <p>为什么做成 ThreadLocal 而不是给工具注入：工具是单例 Bean，而"这一轮该往哪个会话、
 * 哪条消息上发"是**每一轮都不一样的**。工具在 {@code AgentLoop} 的同一线程里被调用，
 * 所以线程本地是最小的传递方式（也自动隔离并发会话）。
 *
 * <p><b>硬限制（必须守）</b>：QQ 机器人对**同一条用户消息**只允许被动回复 5 次
 * （{@code msg_seq} 1~5，见 {@code QqChannel#buildMessageBody}）。最终那条总结也要占一次，
 * 所以进度播报最多留 3 次，超了不发（否则平台直接拒，还会污染日志）。
 */
public final class TurnProgress {

    private static final Logger log = LoggerFactory.getLogger(TurnProgress.class);

    /** 默认每轮最多播报几次（最终回复还要占一次，QQ 一条消息总共只认 5 次） */
    private static final int DEFAULT_MAX = 3;
    /** 两次播报之间至少隔这么久，防刷屏 */
    private static final long MIN_GAP_MILLIS = 3000;
    /** 单条最长（长了不像"顺口说一句"，而且浪费被动额度） */
    private static final int MAX_CHARS = 200;

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TurnProgress() {
    }

    private static final class State {
        final Consumer<String> sender;
        final int max;
        final AtomicInteger sent;
        final AtomicLong lastAt = new AtomicLong(0);

        State(Consumer<String> sender, int max, int alreadySent) {
            this.sender = sender;
            this.max = max;
            this.sent = new AtomicInteger(alreadySent);
        }
    }

    /** 装上一轮的播报通道（{@code AgentOrchestrator} 在调模型前装，finally 里清） */
    public static void install(Consumer<String> sender) {
        install(sender, DEFAULT_MAX);
    }

    public static void install(Consumer<String> sender, int max) {
        if (sender == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new State(sender, Math.max(1, Math.min(3, max)), 0));
    }

    /** 已经播报过几次（最终回复那边可以据此算还能发几条） */
    public static int sentCount() {
        State state = CURRENT.get();
        return state == null ? 0 : state.sent.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 发一条进度，然后**继续干活**。
     *
     * @return 给模型看的一句话：发出去了 / 为什么没发（它会照实转述或直接忽略）
     */
    public static String emit(String text) {
        State state = CURRENT.get();
        if (state == null) {
            // 自主模块跑的那轮没有"用户正在等"这回事，静默拒绝即可
            return "现在不是对话中，没有可以播报的对象。";
        }
        String message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return "没内容可发。";
        }
        if (message.length() > MAX_CHARS) {
            return "太长了（" + message.length() + " 字），播报只说一句短的（≤" + MAX_CHARS + " 字）。";
        }
        if (state.sent.get() >= state.max) {
            return "这一轮已经说过 " + state.sent.get() + " 次了，别再打断他——把手上的事做完，"
                    + "最后一次性说清楚。";
        }
        long now = System.currentTimeMillis();
        long last = state.lastAt.get();
        if (last > 0 && now - last < MIN_GAP_MILLIS) {
            return "刚说过一句，隔 " + ((MIN_GAP_MILLIS - (now - last)) / 1000 + 1)
                    + " 秒再说（别连着刷屏）。";
        }
        try {
            state.sender.accept(message);
            state.sent.incrementAndGet();
            state.lastAt.set(now);
            log.info("进度播报（第 {} 次）：{}", state.sent.get(),
                    message.length() > 60 ? message.substring(0, 60) + "…" : message);
            return "已经发出去了，继续干活。";
        } catch (RuntimeException exception) {
            log.warn("进度播报失败：{}", exception.getMessage());
            return "没发出去（" + exception.getMessage() + "），别重试，继续手上的事。";
        }
    }
}
