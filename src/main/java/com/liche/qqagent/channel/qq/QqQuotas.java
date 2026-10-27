package com.liche.qqagent.channel.qq;

/**
 * 主动消息配额的文字与判定（2026-10-27 从 {@code QqChannel} 拆出，第 6c 步的一部分）。
 *
 * <p>为什么单独拿出来：这两处都只跟"账本怎么读"有关，跟发送流程无关。
 * 而它们的错误表现很隐蔽——日志里显示 `-1/0` 这种噪声、或者配额为 0 时永远发不出去，
 * 排查时都要翻很久。集中成两行纯函数后，一眼能看懂。
 */
final class QqQuotas {

    private QqQuotas() {
    }

    /** 配额账本能不能放行（账本不可用时**放行**——宁可多发一条，也别因为 Redis 挂了完全不说话） */
    static boolean allows(QqProactiveQuota quota) {
        return quota == null || quota.allows();
    }

    /** 日志里那截"今日主动 N/M 条"；账本不可用时只说明情况，不显示 "-1/0" 这种噪声 */
    static String suffix(QqProactiveQuota quota) {
        if (quota == null) {
            return "";
        }
        long used = quota.used();
        if (used < 0) {
            return "，主动额度账本不可用";
        }
        int limit = quota.limit();
        return "，今日主动 " + used + (limit > 0 ? "/" + limit : "") + " 条";
    }
}
