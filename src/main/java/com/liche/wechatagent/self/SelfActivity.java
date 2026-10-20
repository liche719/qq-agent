package com.liche.wechatagent.self;

/**
 * 「它自己的时间」里，这一轮**可以怎么花**（2026-10-20 加，用户要求"以后加小红书这类能力不用改核心"）。
 *
 * <p>原来只有一种方式：开方向 → 查资料 → 写笔记。用户要的是更多种（刷 B 站、以后刷小红书、整理自己的东西……），
 * 而且这些方式之间**要相互知道**（刷到视频 → 有想法 → 研究 → 再定向找视频）。所以把"这一轮干什么"
 * 抽成一个接口：
 *
 * <ul>
 *   <li>一个活动 = 一个 Spring {@code @Component}；{@code SelfActivityJob} 用
 *       {@code List<SelfActivity>} 收全部实现，**加活动不用改注册表**；</li>
 *   <li>{@link #due} 由活动自己判断"今天还欠不欠"（冷却、次数、时段都在这里）；</li>
 *   <li>{@link #instruction} 只给"这一轮干什么"，它自己的近况（念头、最近干过的事）由
 *       {@link SelfLoader} 统一注入——**这才让活动之间相互知道**；</li>
 *   <li>{@link #run} 自己管预算与轮次（现有研究活动那套逻辑原样留在 {@code SelfQuestService} 里）。</li>
 * </ul>
 *
 * <p><b>不做</b>插件热加载：加能力 = 加一个类 + 一段配置 + 一次部署（见 AGENTS.md 的取舍说明）。
 */
public interface SelfActivity {

    /** 身份，日志与面板用（如 {@code research} / {@code bilibili}） */
    String key();

    /** 面板上给人看的名字 */
    String title();

    /** 这一轮还欠不欠（冷却、每日次数、时段、预算都在这里判断） */
    boolean due(ActivityContext context);

    /**
     * 真的跑一轮。**必须自己兜住异常**——一个活动崩了不能把心跳带崩，
     * 更不能再影响下一个活动。
     */
    ActivityOutcome run(ActivityContext context);

    /** 这一轮开始时它知道的"当下"（不塞业务参数，需要什么自己去取） */
    record ActivityContext(String ownerUserId, double spentTodayYuan, double dailyBudgetYuan) {
    }

    /** 一轮的账（成功/跳过都要有说法，日志与面板靠它） */
    record ActivityOutcome(boolean ran, String reason, String activity, String summary,
                           int promptTokens, int completionTokens, long durationMs, double costYuan) {

        public static ActivityOutcome skipped(String activity, String reason) {
            return new ActivityOutcome(false, reason, activity, null, 0, 0, 0, 0);
        }

        public static ActivityOutcome done(String activity, String summary, int promptTokens,
                                           int completionTokens, long durationMs, double costYuan) {
            return new ActivityOutcome(true, null, activity, summary, promptTokens, completionTokens,
                    durationMs, costYuan);
        }
    }
}
