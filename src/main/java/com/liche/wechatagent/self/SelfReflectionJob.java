package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 反思的**触发**（spec §4）：不是"每晚一次"，到点只检查"攒够轮数没有"。
 *
 * <p>三档：`off` 不自动反思 / `step-count`（默认）攒够 N 轮 / 手动（面板排障入口）。
 * `compaction-event` 那一档文档里列了，但**这个代码库没有"上下文压缩"这个事件**，所以不假装支持。
 *
 * <p>它跑在定时线程上、完全在主对话路径之外——这正是先例说的 sleep-time：
 * 主 agent 忙的时候不整合，等没人说话的时候再花一次便宜的调用。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfReflectionJob {

    private static final Logger log = LoggerFactory.getLogger(SelfReflectionJob.class);

    private static final String MODE_STEP_COUNT = "step-count";

    private final SelfService selfService;
    private final SelfReflectionService reflectionService;
    private final String mode;
    private final int turnThreshold;

    public SelfReflectionJob(SelfService selfService,
                             SelfReflectionService reflectionService,
                             @Value("${memory.self-reflect-mode:step-count}") String mode,
                             @Value("${memory.self-reflect-turns:12}") int turnThreshold) {
        this.selfService = selfService;
        this.reflectionService = reflectionService;
        this.mode = mode == null ? MODE_STEP_COUNT : mode.trim().toLowerCase();
        this.turnThreshold = Math.max(1, turnThreshold);
    }

    @Scheduled(cron = "${memory.self-reflect-check-cron:0 */10 * * * *}")
    public void tick() {
        if (!MODE_STEP_COUNT.equals(mode)) {
            return;
        }
        if (!selfService.isActive()) {
            return;
        }
        String owner = selfService.owner();
        if (owner == null) {
            return;
        }
        long turns;
        try {
            turns = selfService.turnsSinceLastReflection();
        } catch (RuntimeException exception) {
            log.warn("自主模块统计轮数失败：{}", exception.getMessage());
            return;
        }
        if (turns < turnThreshold) {
            return;
        }
        try {
            SelfReflectionService.Outcome outcome = reflectionService.reflect(owner, MODE_STEP_COUNT);
            if (outcome.ran()) {
                log.info("自主模块攒够 {} 轮，完成一次反思 id={} 倾向[{}]", turns, outcome.reflectionId(),
                        outcome.stances());
            } else {
                log.info("自主模块已攒 {} 轮，这次没反思：{}", turns, outcome.reason());
            }
        } catch (RuntimeException exception) {
            log.warn("自主模块反思异常：{}", exception.getMessage());
        }
    }
}
