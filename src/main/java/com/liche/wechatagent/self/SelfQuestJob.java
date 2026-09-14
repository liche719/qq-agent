package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「它自己的时间」的定时入口（spec §7：每天给它一笔小预算）。
 *
 * <p>和反思的区别：反思是**攒够轮数**才触发（被机主的对话量驱动），
 * 领域是**每天到点就有一段时间**（§7 的字面意思：每天一笔"自己的时间"）。
 * 预算与防抖在 {@link SelfQuestService} 里，这个类只负责"到点叫它一声"。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfQuestJob {

    private static final Logger log = LoggerFactory.getLogger(SelfQuestJob.class);

    private static final String MODE_DAILY = "daily";

    private final SelfQuestService questService;
    private final String mode;

    public SelfQuestJob(SelfQuestService questService,
                        @Value("${memory.self-quest-mode:daily}") String mode) {
        this.questService = questService;
        this.mode = mode == null ? MODE_DAILY : mode.trim().toLowerCase();
    }

    @Scheduled(cron = "${memory.self-quest-cron:0 40 22 * * *}")
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
    }
}
