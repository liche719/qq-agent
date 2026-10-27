package com.liche.qqagent.channel.qq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 发消息的编排（2026-10-27 从 {@code QqChannel} 拆出，第 6c 步）。
 *
 * <p>三件事一起搬，**不能拆开**：
 * <ol>
 *   <li>{@link #sendPassive}：窗口内就带 msg_id 回，窗口外降级成主动消息；</li>
 *   <li>{@link #sendProactive}：不带 msg_id，受每日配额约束；</li>
 *   <li>{@link #handleSendFailure}：失败后**能不能重发**——这是"同一句话发两遍"的唯一防线。</li>
 * </ol>
 * 拆开搬会出现"一半读新表、一半读旧表"的双份状态（2026-10-27 实测过，当场回滚），
 * 所以三者必须同进同出。
 *
 * <p>它在意的依赖全部通过 {@link Deps} 注入：谁的窗口、谁发的 HTTP、谁的配额、错误怎么记——
 * 这样这个类不碰任何 Spring bean，纯编排，可独立读。
 */
final class QqOutbound {

    private static final Logger log = LoggerFactory.getLogger(QqOutbound.class);

    /** 真正发一条消息（返回平台给的消息 id，失败抛异常） */
    interface Poster {
        String post(String userId, String text, boolean markdown, String replyToMsgId) throws Exception;
    }

    /** 失败处置（本身在 channel 里，因为它要发"重发一次"的动作） */
    record Deps(
            long passiveWindowMillis,
            Runnable prepare,
            Runnable ensureToken,
            Function<String, String> lastMessageId,
            Function<String, Long> lastReceivedAtOf,
            BiFunction<String, String, Long> receivedAt,
            Poster post,
            BiConsumer<String, String> rememberBotMessageId,
            Function<String, Boolean> proactiveAllows,
            Consumer<String> recordProactive,
            Supplier<String> quotaSuffix,
            Consumer<Exception> onApiError,
            Runnable onResultUnknown,
            Function<Exception, String> orphanMessageIdOf,
            BiConsumer<String, String> deleteMessage) {
    }

    private final Deps deps;

    QqOutbound(Deps deps) {
        this.deps = deps;
    }

    /** 被动优先：窗口内带 msg_id，窗口外降级成主动消息 */
    boolean sendPassive(String userId, String replyToMsgId, String text, boolean markdown) {
        boolean withinWindow = false;
        try {
            deps.prepare().run();
            deps.ensureToken().run();
            String targetMsgId = replyToMsgId == null || replyToMsgId.isBlank()
                    ? deps.lastMessageId().apply(userId) : replyToMsgId;
            Long recvAt = replyToMsgId == null || replyToMsgId.isBlank()
                    ? deps.lastReceivedAtOf().apply(userId) : deps.receivedAt().apply(userId, replyToMsgId);
            withinWindow = targetMsgId != null
                    && recvAt != null
                    && (System.currentTimeMillis() - recvAt) < deps.passiveWindowMillis();
            if (!withinWindow) {
                return sendProactive(userId, text, markdown);
            }
            deps.rememberBotMessageId().accept(userId, deps.post().post(userId, text, markdown, targetMsgId));
            log.info("[qq] send(passive) -> {} ({} chars)", userId, text == null ? 0 : text.length());
            return true;
        } catch (Exception exception) {
            deps.onApiError().accept(exception);
            return handleSendFailure(userId, text, markdown, withinWindow, exception);
        }
    }

    /** 主动消息（不带 msg_id）：受每日配额约束，超限就**放弃发送并记 WARN**，而不是发出去等平台拒 */
    boolean sendProactive(String userId, String text, boolean markdown) {
        if (!Boolean.TRUE.equals(deps.proactiveAllows().apply(userId))) {
            return false;
        }
        try {
            deps.ensureToken().run();
            deps.rememberBotMessageId().accept(userId, deps.post().post(userId, text, markdown, null));
            deps.recordProactive().accept(userId);
            log.info("[qq] send(proactive) -> {} ({} chars{})", userId, text == null ? 0 : text.length(),
                    deps.quotaSuffix().get());
            return true;
        } catch (Exception exception) {
            // 交给调用方分类（能不能重发由 QqSendErrors 判断），这里只统一成运行时异常
            throw exception instanceof RuntimeException runtime ? runtime : new RuntimeException(exception);
        }
    }

    /**
     * 发送失败后的处置（这里修的是"同一条消息发两遍"）。
     *
     * <p>能不能重发，取决于**这次失败到底有没有把消息发出去**：
     * <ul>
     *   <li>响应体里带了消息 id：QQ 其实已经发出去了（只是响应本身报错）→ 先撤掉它，重发不会重复；</li>
     *   <li>4xx：服务端明确拒收（msg_id 失效、窗口过期、令牌失效、限频）→ 没发出去，重发安全；</li>
     *   <li>超时、连接中断、5xx：**结果未知**，请求可能已经送达。按"已发出"处理——被动回复返回 true，
     *       让上层不要再走标准回复路径；宁可偶尔少一条，也不要重复刷屏。次数记在面板可见的计数上。</li>
     * </ul>
     */
    boolean handleSendFailure(String userId, String text, boolean markdown, boolean wasPassive, Exception cause) {
        String orphanMessageId = deps.orphanMessageIdOf().apply(cause);
        if (orphanMessageId != null && !orphanMessageId.isBlank()) {
            deps.deleteMessage().accept(userId, orphanMessageId);
        } else if (QqSendErrors.resultUnknown(cause)) {
            deps.onResultUnknown().run();
            log.warn("[qq] {}发送结果未知（{}），不再重发以免重复 user={}{}",
                    wasPassive ? "被动" : "主动", cause.getClass().getSimpleName(), userId, deps.quotaSuffix().get());
            return wasPassive;
        }
        String reason = orphanMessageId != null && !orphanMessageId.isBlank() ? "响应里带消息 id，已撤回"
                : QqSendErrors.definiteRejection(cause) ? "服务端明确拒收" : "连接未建立，肯定没送达";
        log.warn("[qq] {}发送失败（{}）user={}: {} → 重发一次主动消息{}", wasPassive ? "被动" : "主动", reason,
                userId, cause.getMessage(), deps.quotaSuffix().get());
        try {
            return sendProactive(userId, text, markdown);
        } catch (RuntimeException retryFailure) {
            deps.onApiError().accept(retryFailure);
            log.warn("[qq] 重发失败 user={}: {}", userId, retryFailure.getMessage());
            return false;
        }
    }
}
