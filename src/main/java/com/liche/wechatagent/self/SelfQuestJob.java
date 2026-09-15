package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「它自己的时间」的入口（spec §7）。
 *
 * <p><b>不看钟点</b>（2026-09-15 用户定："什么时候想说就什么时候说"）：这个心跳每 10 分钟看一眼，
 * **它现在想不想动**由 {@link SelfQuestService#wantsToWork()} 判断——手上有事在推进、或者搁太久了
 * 还有没结的事。满足条件才真的叫它；额度（每天 ≤N 次）、防抖、"今天先到这"照旧是硬闸。
 *
 * <p>嘴是**独立**的：它写下想说的话之后，作业跑完立刻发；作业没跑时，攒着的话也会在这一次
 * 心跳里发出去——不为"它今天动没动"卡住。
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

    @Scheduled(cron = "${memory.self-quest-check-cron:0 */10 * * * *}")
    public void tick() {
        if (!MODE_DAILY.equals(mode)) {
            return;
        }
        try {
            if (questService.wantsToWork()) {
                SelfQuestService.Outcome outcome = questService.run("triggered");
                if (outcome.ran()) {
                    log.info("它自己的时间：方向={} run={} tokens={}/{} 用时 {}ms",
                            outcome.questId(), outcome.runId(), outcome.promptTokens(),
                            outcome.completionTokens(), outcome.durationMs());
                } else {
                    log.info("它想动但这次没跑成：{}", outcome.reason());
                }
            }
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
}
