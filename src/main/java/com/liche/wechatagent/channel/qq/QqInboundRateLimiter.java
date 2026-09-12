package com.liche.wechatagent.channel.qq;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 入站消息的按用户限流（固定窗口计数）。
 *
 * <p>目的不是防攻击（QQ 网关侧已经鉴权），而是防"用户自己连点/刷屏"：每条入站会进每用户的串行队列、
 * 逐条调用大模型，一个人猛发几十条会把队列、模型额度和记忆提取全占满，正常用户反而被挤住。
 *
 * <p>超限后的处理是**先礼貌回一条、再静默丢弃**：只回一次（同一个窗口内不重复回），
 * 否则限流提示自己又变成刷屏。
 */
final class QqInboundRateLimiter {

    enum Decision {
        /** 正常处理 */
        ALLOW,
        /** 超限，且本窗口还没提醒过 —— 回一条礼貌提示 */
        NOTIFY,
        /** 超限，直接丢弃 */
        DROP
    }

    private final int limitPerWindow;
    private final long windowMillis;
    private final int maxEntries;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        private long windowStart;
        private int count;
        private boolean notified;

        Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }

    QqInboundRateLimiter(int limitPerWindow, long windowMillis, int maxEntries) {
        this.limitPerWindow = limitPerWindow;
        this.windowMillis = windowMillis;
        this.maxEntries = maxEntries;
    }

    boolean enabled() {
        return limitPerWindow > 0;
    }

    int limitPerWindow() {
        return limitPerWindow;
    }

    Decision tryAcquire(String userId, long now) {
        if (!enabled() || userId == null || userId.isBlank()) {
            return Decision.ALLOW;
        }
        Window window = windows.computeIfAbsent(userId, key -> new Window(now));
        synchronized (window) {
            if (now - window.windowStart >= windowMillis) {
                window.windowStart = now;
                window.count = 0;
                window.notified = false;
            }
            window.count++;
            if (window.count <= limitPerWindow) {
                return Decision.ALLOW;
            }
            if (!window.notified) {
                window.notified = true;
                return Decision.NOTIFY;
            }
            return Decision.DROP;
        }
    }

    /** 清掉早就过期的窗口，避免只说过一次话的用户永远占着一条记录 */
    void prune(long now) {
        if (windows.isEmpty()) {
            return;
        }
        windows.entrySet().removeIf(entry -> {
            Window window = entry.getValue();
            synchronized (window) {
                return now - window.windowStart >= windowMillis * 2;
            }
        });
        int overflow = windows.size() - maxEntries;
        if (overflow > 0) {
            windows.entrySet().stream().limit(overflow).forEach(entry -> windows.remove(entry.getKey()));
        }
    }

    void clear() {
        windows.clear();
    }
}
