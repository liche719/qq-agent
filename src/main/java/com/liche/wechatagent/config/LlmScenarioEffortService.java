package com.liche.wechatagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 思考强度的**面板覆盖值**（2026-09-29 加）：让"调一下看看效果"不用改 .env + 重启容器。
 *
 * <p>读取路径：{@link LlmScenarioSettings#reasoningEffortFor} 先问这里，命中就用这里的值，
 * 否则回落到 {@code llm.reasoning-effort} 的配置默认值。写的是内存里的 {@link ConcurrentHashMap}，
 * **模型调用线程上没有任何数据库访问**——表只在启动时读一次、改动时写一次。
 *
 * <p>「选默认」的语义是**删行**，不是存一个 "default"：这样"回落到配置文件"只有一个来源，
 * 不会出现"库里写着 low、配置里写着 high、到底哪个算"的歧义。
 */
@Component
public class LlmScenarioEffortService {

    private static final Logger log = LoggerFactory.getLogger(LlmScenarioEffortService.class);

    /** 允许的档位；空字符串表示"不传这个字段、用上游默认" */
    public static final String DEFAULT = "";
    public static final List<String> EFFORTS = List.of("low", "medium", "high");

    private final LlmScenarioSettingRepository repository;
    private final ZoneId zone;
    /** 场景 → 覆盖值；只在启动时整表读一次，之后由 {@link #save} 维护 */
    private final Map<LlmScenario, String> overrides = new ConcurrentHashMap<>();

    public LlmScenarioEffortService(LlmScenarioSettingRepository repository,
                                    @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.repository = repository;
        this.zone = parseZone(timeZone);
        for (LlmScenarioSetting row : repository.findAll()) {
            LlmScenario scenario = parse(row.getScenario());
            if (scenario == null) {
                // 认不出的场景名（比如改名后残留）直接忽略，**绝不能**让它落到 DIALOG 上
                log.warn("思考强度覆盖值里有认不出的场景，已忽略：{}", row.getScenario());
                continue;
            }
            overrides.put(scenario, normalize(row.getReasoningEffort()));
        }
        if (!overrides.isEmpty()) {
            log.info("思考强度覆盖值已加载：{}", overrides);
        }
    }

    /** 这个场景被面板覆盖成了什么；null = 没覆盖，用配置默认值 */
    public String overrideFor(LlmScenario scenario) {
        return overrides.get(scenario);
    }

    /**
     * 保存一个场景的思考强度。
     *
     * @param effort {@code low}/{@code medium}/{@code high}；空串或 null = 删掉覆盖、回落到配置
     * @return 是否接受（false = 场景名或档位不认识）
     */
    public boolean save(String scenarioKey, String effort) {
        LlmScenario scenario = parse(scenarioKey);
        if (scenario == null) {
            return false;
        }
        String value = normalize(effort);
        if (!value.isEmpty() && !EFFORTS.contains(value)) {
            return false;
        }
        if (value.isEmpty()) {
            repository.deleteById(scenario.label());
            overrides.remove(scenario);
            return true;
        }
        LlmScenarioSetting row = new LlmScenarioSetting();
        row.setScenario(scenario.label());
        row.setReasoningEffort(value);
        row.setUpdatedAt(LocalDateTime.now(zone));
        repository.save(row);
        overrides.put(scenario, value);
        return true;
    }

    /** 面板覆盖值的只读快照（场景 → 档位），只含被覆盖过的 */
    public Map<String, String> current() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (LlmScenario scenario : LlmScenario.values()) {
            String value = overrides.get(scenario);
            if (value != null) {
                snapshot.put(scenario.label(), value);
            }
        }
        return snapshot;
    }

    private static String normalize(String effort) {
        String value = effort == null ? "" : effort.trim().toLowerCase();
        return "default".equals(value) || "none".equals(value) ? "" : value;
    }

    /** 严格解析场景名：认不出就返回 null（**不要**退回 DIALOG，那会悄悄改掉对话的档位） */
    private static LlmScenario parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim().toLowerCase().replace('-', '_');
        for (LlmScenario scenario : LlmScenario.values()) {
            if (scenario.label().equals(wanted)) {
                return scenario;
            }
        }
        return null;
    }

    private static ZoneId parseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
