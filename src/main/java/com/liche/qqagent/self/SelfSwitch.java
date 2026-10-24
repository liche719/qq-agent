package com.liche.qqagent.self;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 自主模块的**运行时开关**（2026-10-24）——面板上能一键关掉"它自己"那块。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty} 现成的 `memory.self-enabled`：那是**启动期**判定，
 * 改它要重建容器。用户要的是"前端点一下立刻生效"，所以开关值放 Redis（AOF 开着，重启不丢），
 * 环境变量只当**默认值**（Redis 里没有记录时用它）。
 *
 * <p>关掉后三处一起停：
 * <ol>
 *   <li>心跳/反思任务直接跳过（{@link SelfActivityJob}、{@link SelfReflectionJob}）；</li>
 *   <li>自主模块的提示词段不再注入（{@link SelfLoader}）；</li>
 *   <li>模型看不到那些工具（{@code AgentLoop} 里按 provider 过滤）。</li>
 * </ol>
 */
@Component
public class SelfSwitch {

    private static final String KEY = "self:enabled";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SelfSwitch.class);

    private final StringRedisTemplate redis;
    private final boolean defaultEnabled;

    public SelfSwitch(StringRedisTemplate redis,
                      @Value("${memory.self-enabled:false}") boolean defaultEnabled) {
        this.redis = redis;
        this.defaultEnabled = defaultEnabled;
    }

    /** 当前是否启用：优先 Redis 里的开关值，没有就用环境变量默认值 */
    public boolean enabled() {
        try {
            String value = redis.opsForValue().get(KEY);
            if (value != null && !value.isBlank()) {
                return "true".equalsIgnoreCase(value.trim());
            }
        } catch (RuntimeException exception) {
            log.warn("读自主模块开关失败，按默认值 {} 处理：{}", defaultEnabled, exception.getMessage());
        }
        return defaultEnabled;
    }

    /** 面板切换；返回是否真的写进去了（Redis 不可用时会失败） */
    public boolean set(boolean enabled) {
        try {
            redis.opsForValue().set(KEY, enabled ? "true" : "false");
            log.info("自主模块开关被面板改成 {}（默认值={}）", enabled, defaultEnabled);
            return true;
        } catch (RuntimeException exception) {
            log.warn("写自主模块开关失败：{}", exception.getMessage());
            return false;
        }
    }

    /** 面板要显示"当前值从哪来" */
    public String source() {
        try {
            String value = redis.opsForValue().get(KEY);
            return value == null || value.isBlank() ? "env" : "panel";
        } catch (RuntimeException exception) {
            return "env";
        }
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }
}
