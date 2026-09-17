package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.LlmSpendMeter;
import com.liche.wechatagent.config.LlmUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 记忆提取的审计落库（2026-09-17）。
 *
 * <p>调用方式：跑之前 {@link #begin()} 取快照，结束时（**含所有提前返回与异常路径**）调
 * {@link #finish(...)} 落一行。token 与钱都靠"前后差值"算：
 * token 来自 {@link LlmUsageRecorder}，钱来自 {@link LlmSpendMeter}（含 cache 命中与峰谷价）。
 *
 * <p>纪律：**审计失败绝不能影响提取本身**——所有异常在这里吞掉并记 WARN。
 */
@Component
public class MemoryExtractionAudit {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionAudit.class);

    private final MemoryExtractionRunRepository repository;
    private final LlmSpendMeter spendMeter;
    private final LlmUsageRecorder usageRecorder;

    public MemoryExtractionAudit(MemoryExtractionRunRepository repository,
                                 LlmSpendMeter spendMeter,
                                 LlmUsageRecorder usageRecorder) {
        this.repository = repository;
        this.spendMeter = spendMeter;
        this.usageRecorder = usageRecorder;
    }

    /** 开始计时/取读数快照 */
    public Span begin() {
        return new Span(System.nanoTime(),
                spendMeter == null ? 0d : spendMeter.totalYuan(),
                spendMeter == null ? 0L : spendMeter.cacheHitTokens(),
                spendMeter == null ? 0L : spendMeter.cacheMissTokens(),
                usageRecorder == null ? LlmUsageRecorder.Snapshot.ZERO : usageRecorder.snapshot());
    }

    /**
     * 落一行审计。
     *
     * @param verdict 模型判定计数（形如 {@code core=1 work=0 ...}），没跑到模型时传 null
     * @param reason  跳过的原因（{@code WINDOW_EMPTY}/{@code PRECHECK}/{@code STALE}/{@code FAILED}），正常跑完传 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String userId, String trigger, Span span, int windowTurns, int windowChars,
                       String verdict, String writtenIds, String reason) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            LlmUsageRecorder.Snapshot used = usageRecorder == null || span == null
                    ? LlmUsageRecorder.Snapshot.ZERO
                    : usageRecorder.snapshot().minus(span.tokens());
            double yuan = spendMeter == null || span == null
                    ? 0d : Math.max(0d, spendMeter.totalYuan() - span.yuan());
            long hit = spendMeter == null || span == null
                    ? 0L : Math.max(0L, spendMeter.cacheHitTokens() - span.cacheHit());
            long miss = spendMeter == null || span == null
                    ? 0L : Math.max(0L, spendMeter.cacheMissTokens() - span.cacheMiss());
            long millis = span == null ? 0L : Math.max(0L, (System.nanoTime() - span.startedNanos()) / 1_000_000L);

            MemoryExtractionRun run = new MemoryExtractionRun();
            run.setUserId(userId);
            run.setTriggerSource(trigger == null ? MemoryExtractionRun.TRIGGER_AUTO : trigger);
            run.setWindowTurns(Math.max(0, windowTurns));
            run.setWindowChars(Math.max(0, windowChars));
            run.setPromptTokens((int) Math.min(Integer.MAX_VALUE, used.prompt()));
            run.setCompletionTokens((int) Math.min(Integer.MAX_VALUE, used.completion()));
            run.setReasoningTokens((int) Math.min(Integer.MAX_VALUE, used.reasoning()));
            run.setCacheHitTokens(hit);
            run.setCacheMissTokens(miss);
            run.setCostYuan(BigDecimal.valueOf(yuan).setScale(6, java.math.RoundingMode.HALF_UP));
            run.setDurationMs((int) Math.min(Integer.MAX_VALUE, millis));
            run.setVerdictJson(clip(verdict, 512));
            run.setWrittenIds(clip(writtenIds, 512));
            run.setSkipReason(reason);
            run.setCreatedAt(LocalDateTime.now());
            repository.save(run);
            log.info("记忆提取审计 #{} user={} window={}轮/{}字 verdict={} written={} reason={} tokens={}/{} 钱={}元 用时={}ms",
                    run.getId(), userId, windowTurns, windowChars, verdict, writtenIds,
                    reason == null ? "-" : reason, used.prompt(), used.completion(),
                    String.format("%.4f", yuan), millis);
        } catch (RuntimeException exception) {
            log.warn("记忆提取审计没写进去（不影响提取本体）：{}", exception.getMessage());
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** 一次提取的起点读数 */
    public record Span(long startedNanos, double yuan, long cacheHit, long cacheMiss,
                       LlmUsageRecorder.Snapshot tokens) {
    }
}
