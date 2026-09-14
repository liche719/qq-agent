package com.liche.wechatagent.self;

import java.time.LocalDateTime;

/**
 * FSRS v6 的遗忘曲线与间隔反推——**纯函数**，倾向复查与教训复查共用同一套数学。
 *
 * <p>公式（照 FSRS v6 实现，不是二手转述）：
 * <ul>
 *   <li>遗忘曲线 {@code R(t,S) = (1 + factor·t/S)^decay}，{@code factor = exp(ln0.9/decay) − 1}，{@code decay < 0}</li>
 *   <li>由目标保留率反推间隔 {@code I(r,S) = (r^(1/decay) − 1)/factor · S}</li>
 * </ul>
 * 所以「该复查了」是**算出来的**（`R` 掉到阈值），不是拍脑袋定的天数。
 *
 * <p><b>诚实说明</b>：{@code S} 的更新用的是**简化规则**（成功 ×(1+(11−D)/20)、失败 ×0.5 且 D+1），
 * 不是 FSRS 官方的 S′ 公式——那要 w8~w14 等 21 个**拟合**参数，我们一条复习数据都还没有。
 * 等攒够复习事件再拟合（spec §13.1「参数留空、数据驱动」的字面意思）。
 */
public final class Fsrs {

    /** FSRS6 默认衰减（配置里可改；传正数也认） */
    public static final double DEFAULT_DECAY = -0.1542;
    /** 默认目标保留率：R 掉到 0.8 就该翻出来复查 */
    public static final double DEFAULT_TARGET = 0.8;

    private Fsrs() {
    }

    /** 还能记住的概率：t 天没碰、强度 S */
    public static double retrievability(double days, double stability, double decay) {
        if (stability <= 0) {
            return 0;
        }
        return Math.pow(1 + factor(decay) * Math.max(0, days) / stability, decay(decay));
    }

    /** 由目标保留率反推间隔天数 */
    public static double intervalDays(double targetRetention, double stability, double decay) {
        double safeDecay = decay(decay);
        double interval = (Math.pow(targetRetention, 1 / safeDecay) - 1) / factor(decay) * Math.max(0.5, stability);
        return Math.max(1, interval);
    }

    /** 复习成功：S 变长（越容易记得住、D 越小，长得越快） */
    public static double stabilityOnSuccess(double stability, double difficulty) {
        double grow = 1 + (11 - clampDifficulty(difficulty)) / 20.0;
        return Math.min(3650, Math.max(0.5, stability) * grow);
    }

    /** 又犯了同类错 / 被反例推翻：S 收缩、D 上升 */
    public static double stabilityOnFailure(double stability) {
        return Math.max(0.5, Math.max(0.5, stability) * 0.5);
    }

    public static double difficultyOnFailure(double difficulty) {
        return Math.min(10, clampDifficulty(difficulty) + 1);
    }

    public static LocalDateTime nextReviewAt(LocalDateTime lastReview, double stability, double decay,
                                             double targetRetention) {
        LocalDateTime base = lastReview == null ? LocalDateTime.now() : lastReview;
        long minutes = Math.round(intervalDays(targetRetention, stability, decay) * 1440);
        return base.plusMinutes(Math.max(60, minutes));
    }

    /** 归一化：文档里的 decay 是负数；传正数/0 都能用 */
    public static double decay(double decay) {
        if (decay > 0) {
            return -decay;
        }
        return decay == 0 ? DEFAULT_DECAY : decay;
    }

    private static double clampDifficulty(double difficulty) {
        return Math.min(10, Math.max(1, difficulty));
    }

    private static double factor(double decay) {
        return Math.exp(Math.log(0.9) / decay(decay)) - 1;
    }
}
