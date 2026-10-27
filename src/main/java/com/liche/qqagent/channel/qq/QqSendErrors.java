package com.liche.qqagent.channel.qq;

import org.springframework.web.client.RestClientResponseException;

/**
 * 发送失败的分类（2026-10-27 从 {@code QqChannel} 拆出，第 6c 步的前置）。
 *
 * <p>这里只回答一个问题：**这次失败到底有没有可能已经送达？**
 * 它决定失败后能不能安全重发——判错的两种后果都很实际：
 *
 * <ul>
 *   <li>该重发却当成"结果未知"→ 白丢一条本来能补发的回复；</li>
 *   <li>不该重发却重发 → 用户收到两条一样的消息。</li>
 * </ul>
 *
 * <p>所以这三个判据单独成类并配单元测试，不再埋在 1800 行的 channel 里。
 */
final class QqSendErrors {

    private QqSendErrors() {
    }

    /** 服务端明确拒收（4xx）＝ 这条消息没有被受理，重发不会产生重复 */
    static boolean definiteRejection(Throwable error) {
        return error instanceof RestClientResponseException response
                && response.getStatusCode().is4xxClientError();
    }

    /**
     * 连接阶段就失败（连接超时 / 连接被拒 / 域名解析不了）＝ 请求**一个字节都没送到**服务端，重发不会重复。
     *
     * <p>必须和"读超时"分开：读超时是请求已经发出去、只是没等到响应，结果未知（可能已经送达）；
     * 而连接没建立起来的请求根本没被处理过。实测踩到的正是这一条：掐断到 QQ API 的出站后，
     * 所有发送都是 `Connect timed out`，原来的"结果未知"分支会**白白丢掉一条能安全补发的回复**。
     * 区分方法：连接失败的 {@code SocketTimeoutException} 消息是 "connect timed out"，读超时是 "Read timed out"。
     */
    static boolean neverReachedServer(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException) {
                return true;
            }
            if (cause instanceof java.net.SocketTimeoutException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect")) {
                return true;
            }
        }
        return false;
    }

    /** 结果未知：请求可能已经送达，**不能重发** */
    static boolean resultUnknown(Throwable error) {
        return !definiteRejection(error) && !neverReachedServer(error);
    }
}
