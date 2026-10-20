package com.liche.wechatagent.self;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 「研究我自己的方向」——把原来那个 {@link SelfQuestService} **原样**包成一个活动（2026-10-20）。
 *
 * <p>这一层**不碰任何判定逻辑**：预算、兴趣阈值、搁太久、轮次上限、笔记要求、说话，全都还在
 * {@code SelfQuestService} 里。所以重构前后跑出来的行为应当**完全一致**——这是抽
 * {@link SelfActivity} 接口时的首要约束（用户在 09-15 定的"它自己的事占大头"，不能因为重构丢了）。
 *
 * <p>它排在最前面：研究方向是它自己的正事，刷视频那类活动只能在没有正事时插进来。
 */
@Component
public class ResearchActivity implements SelfActivity {

    private final SelfQuestService questService;
    /** 用户 2026-10-20 要求能给研究单独关掉（只留刷视频这类活动） */
    private final boolean enabled;

    public ResearchActivity(SelfQuestService questService,
                            @Value("${memory.self-research-enabled:true}") boolean enabled) {
        this.questService = questService;
        this.enabled = enabled;
    }

    @Override
    public String key() {
        return "research";
    }

    @Override
    public String title() {
        return "研究我自己的方向";
    }

    @Override
    public boolean due(ActivityContext context) {
        if (!enabled) {
            return false;
        }
        // 委托给原来那套闸（预算 / 兴趣累积 / 搁太久 + open loop / 之间至少隔 N 分钟）
        return questService.wantsToWork();
    }

    @Override
    public ActivityOutcome run(ActivityContext context) {
        SelfQuestService.Outcome outcome = questService.run("triggered");
        if (!outcome.ran()) {
            return ActivityOutcome.skipped(key(), outcome.reason());
        }
        return ActivityOutcome.done(key(), outcome.summary(), outcome.promptTokens(),
                outcome.completionTokens(), outcome.durationMs(), outcome.costYuan());
    }
}


