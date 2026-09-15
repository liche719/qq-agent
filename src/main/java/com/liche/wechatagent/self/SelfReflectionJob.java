package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 反思的**触发**（spec §4 / §13.2）：不是"每晚一次"，也**不再只由机主的消息量决定**。
 *
 * <p>三条触发源，各自独立：
 * <ol>
 *   <li>{@code step-count}：机主说了够多轮（原来的那一档，保留）。</li>
 *   <li>{@code interest}：**它自己**产生的事件按重要度累加过阈值——Generative Agents 用的就是这个判据。
 *       这条**完全不依赖机主说话**，是这个模块"自己会想"的关键：用机主的消息量当唯一触发，
 *       它的思考就变成机主对话量的影子。</li>
 *   <li>{@code idle}：很久没动自己这边、而且手上还有没结的事（§8 的 open loop：没做完的事会被反复想起）。</li>
 * </ol>
 *
 * <p>{@code mode=off} 是**总闸**：关掉之后三条都不自动跑，只剩面板上的手动入口。
 * 预算（每天 ≤N 次）与防抖仍在 {@link SelfReflectionService} 里，加的是**触发源**、不是加预算。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfReflectionJob {

    private static final Logger log = LoggerFactory.getLogger(SelfReflectionJob.class);

    private static final String MODE_STEP_COUNT = "step-count";
    private static final String MODE_OFF = "off";

    private static final String TRIGGER_STEP_COUNT = "step-count";
    private static final String TRIGGER_INTEREST = "interest";
    private static final String TRIGGER_IDLE = "idle";

    private final SelfService selfService;
    private final SelfReflectionService reflectionService;
    private final String mode;
    private final int turnThreshold;
    private final boolean onInterest;
    private final int interestThreshold;
    private final boolean onIdle;
    private final int idleHours;

    public SelfReflectionJob(SelfService selfService,
                             SelfReflectionService reflectionService,
                             @Value("${memory.self-reflect-mode:step-count}") String mode,
                             @Value("${memory.self-reflect-turns:12}") int turnThreshold,
                             @Value("${memory.self-reflect-on-interest:true}") boolean onInterest,
                             @Value("${memory.self-reflect-interest-threshold:150}") int interestThreshold,
                             @Value("${memory.self-reflect-on-idle:true}") boolean onIdle,
                             @Value("${memory.self-reflect-idle-hours:20}") int idleHours) {
        this.selfService = selfService;
        this.reflectionService = reflectionService;
        this.mode = mode == null ? MODE_STEP_COUNT : mode.trim().toLowerCase();
        this.turnThreshold = Math.max(1, turnThreshold);
        this.onInterest = onInterest;
        this.interestThreshold = Math.max(1, interestThreshold);
        this.onIdle = onIdle;
        this.idleHours = Math.max(1, idleHours);
    }

    @Scheduled(cron = "${memory.self-reflect-check-cron:0 */10 * * * *}")
    public void tick() {
        if (MODE_OFF.equals(mode)) {
            return;
        }
        if (!selfService.isActive()) {
            return;
        }
        String owner = selfService.owner();
        if (owner == null) {
            return;
        }
        String trigger;
        try {
            trigger = pickTrigger();
        } catch (RuntimeException exception) {
            log.warn("自主模块判断该不该反思时出错：{}", exception.getMessage());
            return;
        }
        if (trigger == null) {
            return;
        }
        try {
            SelfReflectionService.Outcome outcome = reflectionService.reflect(owner, trigger);
            if (outcome.ran()) {
                log.info("自主模块反思完成 触发={} id={} 倾向[{}]", trigger, outcome.reflectionId(),
                        outcome.stances());
            } else {
                log.info("自主模块这次没反思 触发={}：{}", trigger, outcome.reason());
            }
        } catch (RuntimeException exception) {
            log.warn("自主模块反思异常：{}", exception.getMessage());
        }
    }

    /** 这次该用哪条触发源；都不满足就返回 null（不反思）。 */
    private String pickTrigger() {
        if (MODE_STEP_COUNT.equals(mode) && selfService.turnsSinceLastReflection() >= turnThreshold) {
            return TRIGGER_STEP_COUNT;
        }
        if (onInterest && selfService.interestSinceLastReflection() >= interestThreshold) {
            return TRIGGER_INTEREST;
        }
        if (onIdle && idleEnough() && selfService.hasOpenLoops()) {
            return TRIGGER_IDLE;
        }
        return null;
    }

    private boolean idleEnough() {
        Duration since = selfService.sinceLastEvent();
        return since != null && !since.isNegative() && since.toHours() >= idleHours;
    }
}
