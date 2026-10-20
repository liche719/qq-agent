package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「它自己的时间」的入口（2026-10-20 改造，原来是 {@code SelfQuestJob}）。
 *
 * <p><b>不看钟点</b>（2026-09-15 用户定："什么时候想说就什么时候说"）：心跳每 10 分钟看一眼，
 * **想不想动**由各个活动自己的 {@link SelfActivity#due} 判断。**一轮只跑一个活动**，
 * 按注入顺序（{@code ResearchActivity} 带 {@code @Order(0)} 排最前）——研究方向是正事，
 * 刷视频那类只能在没有正事时插进来。
 *
 * <p>为什么用 {@code List<SelfActivity>} 而不是写死几个 if：用户 2026-10-20 明确要
 * "以后加小红书这类能力不用改核心"。Spring 会把所有 {@code SelfActivity} 实现自动收进来，
 * **加一个活动 = 加一个 {@code @Component} 类**，这个类一个字都不用改。
 *
 * <p>嘴是**独立**的：它写下想说的话之后，作业跑完立刻发；作业没跑时，攒着的话也会在这一次
 * 心跳里发出去——不为"它今天动没动"卡住。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfActivityJob {

    private static final Logger log = LoggerFactory.getLogger(SelfActivityJob.class);
    private static final String MODE_DAILY = "daily";

    private final List<SelfActivity> activities;
    private final SelfQuestStore selfQuests;
    private final SelfSpeakService speakService;
    private final String mode;
    private final String owner;
    private final double dailyBudgetYuan;
    /**
     * 整个自主模块的**峰谷总闸**（2026-10-20 加）。
     *
     * <p>用户要求：「dsapi 低谷期间才生效」。原来只有"刷 B 站"躲峰，研究活动照样在峰时烧钱；
     * 现在提到这里统一挡——峰时连 {@link SelfActivity#due} 都不问，直接不动。
     */
    private final boolean offPeakOnly;

    public SelfActivityJob(List<SelfActivity> activities,
                           SelfQuestStore selfQuests,
                           SelfSpeakService speakService,
                           @Value("${memory.self-quest-mode:daily}") String mode,
                           @Value("${memory.self-owner-openid:}") String owner,
                           @Value("${memory.self-quest-daily-budget-yuan:0.5}") double dailyBudgetYuan,
                           @Value("${memory.self-offpeak-only:true}") boolean offPeakOnly) {
        this.activities = activities;
        this.selfQuests = selfQuests;
        this.speakService = speakService;
        this.mode = mode == null ? MODE_DAILY : mode.trim().toLowerCase();
        this.owner = owner == null ? "" : owner.trim();
        this.dailyBudgetYuan = dailyBudgetYuan;
        this.offPeakOnly = offPeakOnly;
    }

    // 2026-10-20：心跳从「每 10 分钟」改成「每 2 小时」——它要做的事都不着急，
    // 10 分钟一次纯属白问；真正决定干不干活的是各个活动的 due()。
    @Scheduled(cron = "${memory.self-quest-check-cron:0 0 */2 * * *}")
    public void tick() {
        if (!MODE_DAILY.equals(mode)) {
            return;
        }
        if (offPeakOnly && SelfOffPeak.isPeakNow()) {
            log.debug("现在是 DeepSeek 峰时（贵一倍），自主模块不动；{} 之后再说", SelfOffPeak.describeNow());
            return;
        }
        SelfActivity.ActivityContext context = new SelfActivity.ActivityContext(
                owner, selfQuests.questCostToday().doubleValue(), dailyBudgetYuan);
        try {
            runOne(context);
        } catch (RuntimeException exception) {
            log.warn("它自己的时间异常：{}", exception.getMessage());
        }
        // 嘴独立于作业：刚写完的立刻发；作业没跑时，之前攒下的也照样发得出去
        try {
            SelfSpeakService.Outcome spoken = speakService.flush();
            if (spoken.sent()) {
                log.info("它主动跟机主说了一条 #{}", spoken.utteranceId());
            } else {
                log.debug("这次没说：{}", spoken.reason());
            }
        } catch (RuntimeException exception) {
            log.warn("它想说话但出错：{}", exception.getMessage());
        }
    }

    /** 按顺序找一个"欠着"的活动跑；全都不欠就什么都不做（那就真是它不想动） */
    private void runOne(SelfActivity.ActivityContext context) {
        for (SelfActivity activity : activities) {
            boolean due;
            try {
                due = activity.due(context);
            } catch (RuntimeException exception) {
                log.warn("活动 {} 判断 due 时出错，跳过：{}", activity.key(), exception.getMessage());
                continue;
            }
            if (!due) {
                continue;
            }
            SelfActivity.ActivityOutcome outcome;
            try {
                outcome = activity.run(context);
            } catch (RuntimeException exception) {
                log.warn("活动 {} 跑失败了：{}", activity.key(), exception.getMessage());
                return;
            }
            if (outcome.ran()) {
                log.info("它自己的时间：活动={} 用时 {}ms tokens={}/{} 花 {} 元 结论：{}",
                        outcome.activity(), outcome.durationMs(), outcome.promptTokens(),
                        outcome.completionTokens(), String.format(java.util.Locale.ROOT, "%.3f", outcome.costYuan()),
                        outcome.summary());
            } else {
                log.info("活动 {} 想动但这次没跑成：{}", activity.key(), outcome.reason());
            }
            return;
        }
    }
}
