package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「它自己的时间」的定时入口（spec §7：每天给它一笔预算）。
 *
 * <p><b>三次机会、额度归它</b>：默认 10:00 / 16:00 / 22:00 各叫它一次，今天用几次由它自己决定——
 * 用完额度就停，或者它主动用 {@code selfQuestRest} 说"今天先到这"。
 * 程序只保证两件事：**到点叫它一声**、**额度别超**；要不要动、动多久，不替它决定。
 *
 * <p>和反思的区别：反思由它**自己事件的兴趣累积**（外加机主轮数、闲置）触发；
 * 领域是**固定的几次机会**，交给它自己去用。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfQuestJob {

    private static final Logger log = LoggerFactory.getLogger(SelfQuestJob.class);

    private static final String MODE_DAILY = "daily";

    private final SelfQuestService questService;
    private final SelfSpeakService speakService;
    private final String mode;

    public SelfQuestJob(SelfQuestService questService,
                        SelfSpeakService speakService,
                        @Value("${memory.self-quest-mode:daily}") String mode) {
        this.questService = questService;
        this.speakService = speakService;
        this.mode = mode == null ? MODE_DAILY : mode.trim().toLowerCase();
    }

    @Scheduled(cron = "${memory.self-quest-cron:0 0 7,13,20 * * *}")
    public void tick() {
        if (!MODE_DAILY.equals(mode)) {
            return;
        }
        try {
            SelfQuestService.Outcome outcome = questService.run("daily");
            if (outcome.ran()) {
                log.info("它自己的时间：方向={} run={} tokens={}/{} 用时 {}ms",
                        outcome.questId(), outcome.runId(), outcome.promptTokens(),
                        outcome.completionTokens(), outcome.durationMs());
            } else {
                log.info("它自己的时间这次没跑：{}", outcome.reason());
            }
        } catch (RuntimeException exception) {
            log.warn("它自己的时间异常：{}", exception.getMessage());
        }
        // 它刚想完的事，如果它决定要说，就趁这个窗口说出去。
        // **放在作业之后**：这一步刚想到的能立刻说；作业被跳过（额度用完/它说今天先到这）时，
        // 之前攒下的也照样发得出去——嘴和作业是两件事。
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
}
