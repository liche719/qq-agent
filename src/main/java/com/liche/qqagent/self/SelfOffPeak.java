package com.liche.qqagent.self;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * DeepSeek 的**峰谷时段**判断（2026-10-20 从 {@link BilibiliActivity} 抽出来公用）。
 *
 * <p>为什么值得单独一个类：用户的要求是「dsapi 低谷期间才生效」——不是只让"刷 B 站"躲峰，
 * 而是**整个自主模块**都躲。原来这段判断藏在刷视频那个活动里，研究活动就躲不开。
 *
 * <p>口径（官方按 UTC 划，换算到北京时间）：**工作日 09–12、14–18 是峰时，价格翻倍**；
 * 周末全天算谷时。自主模块做的事都不着急，没有理由在贵一倍的时候跑。
 */
public final class SelfOffPeak {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private SelfOffPeak() {
    }

    /** 按北京时间算，现在是不是峰时 */
    public static boolean isPeakNow() {
        return isPeak(LocalDateTime.now(ZONE));
    }

    /** 按北京时间算，给定时刻是不是峰时（周末整天都算谷时） */
    public static boolean isPeak(LocalDateTime beijing) {
        DayOfWeek day = beijing.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = beijing.toLocalTime();
        return (!time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(12, 0)))
                || (!time.isBefore(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(18, 0)));
    }

    /** 给日志/面板看的一句话 */
    public static String describeNow() {
        return isPeakNow() ? "峰时（避开）" : "谷时（可以干活）";
    }
}
