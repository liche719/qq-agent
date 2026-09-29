package com.liche.wechatagent.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * 每个调用场景的「档位」：温度多少、{@code max_tokens} 给多少。
 *
 * <p>**2026-09-14：按用户要求删掉了"按场景开关深度思考"**。原实现是一个 {@code thinking:{"type":"disabled"}}
 * 的请求体字段 + {@code llm.thinking.*} 配置（当时的实测结论：这个模型默认就在思考，唯一有效的关闭方式就是那个字段；
 * {@code enable_thinking:false} 与 {@code chat_template_kwargs} 会被静默忽略）。但用户的实际体感是
 * "省电档让它显得变傻了"，而**只有对话省电档能省到等待时间**，其余场景省的是后台 token——
 * 于是整块能力删掉，**默认全部思考**。历史实测数据保留在 {@code docs/llm-call-modes.md}。
 *
 * <p>温度仍然按场景区分（用户明确要求保留）：结构化输出要稳定，用 0.0；对话要自然，用全局 {@code llm.temperature}。
 */
public class LlmScenarioSettings {

    /** 默认温度 0 的场景：只要结构化输出正确，不需要发散 */
    private static final Set<LlmScenario> DEFAULT_ZERO_TEMPERATURE = EnumSet.of(
            LlmScenario.EXTRACT, LlmScenario.REFLECT, LlmScenario.REMINDER_PARSE, LlmScenario.SCHEDULE_PARSE);

    private final Set<LlmScenario> zeroTemperature;
    private final int structuredMaxTokens;
    private final int reflectMaxTokens;
    private final int dialogMaxTokens;
    private final int dialogDeepMaxTokens;
    /**
     * 每个场景的"思考档位"（请求体的 {@code reasoning_effort}）：2026-09-18 用户要求「对话 low、提取 high」。
     * 实测（同一道题各 4 次采样）：low 思考 984 token / 4.8s，不传 3004 / 14.0s，high 2368 / 11.2s——差别是真的。
     * 没配的场景**不传**这个字段（保持上游默认）。配置形如 {@code dialog=low,extract=high}。
     */
    private final java.util.Map<LlmScenario, String> reasoningEffort;

    public LlmScenarioSettings(String zeroTemperatureScenarios,
                               int structuredMaxTokens, int dialogMaxTokens, int dialogDeepMaxTokens) {
        this(zeroTemperatureScenarios, structuredMaxTokens, dialogMaxTokens, dialogDeepMaxTokens, 16384, null);
    }

    public LlmScenarioSettings(String zeroTemperatureScenarios,
                               int structuredMaxTokens, int dialogMaxTokens, int dialogDeepMaxTokens,
                               int reflectMaxTokens) {
        this(zeroTemperatureScenarios, structuredMaxTokens, dialogMaxTokens, dialogDeepMaxTokens, reflectMaxTokens,
                null);
    }

    public LlmScenarioSettings(String zeroTemperatureScenarios,
                               int structuredMaxTokens, int dialogMaxTokens, int dialogDeepMaxTokens,
                               int reflectMaxTokens, String reasoningEffortScenarios) {
        this.structuredMaxTokens = Math.max(0, structuredMaxTokens);
        this.reflectMaxTokens = Math.max(0, reflectMaxTokens);
        this.dialogMaxTokens = Math.max(0, dialogMaxTokens);
        this.dialogDeepMaxTokens = Math.max(0, dialogDeepMaxTokens);
        this.zeroTemperature = parseScenarios(zeroTemperatureScenarios, DEFAULT_ZERO_TEMPERATURE);
        this.reasoningEffort = parseReasoningEffort(reasoningEffortScenarios);
    }

    /**
     * 面板上的**覆盖值**（2026-09-29 加，见 {@link LlmScenarioEffortService}）。
     * 用 setter + {@code required=false} 注入而不是构造器参数：这个类有多个给单测用的构造器，
     * 加构造器参数踩过坑（坑 45，曾把线上打挂两个部署周期）。
     */
    private LlmScenarioEffortService effortOverrides;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEffortOverrides(LlmScenarioEffortService effortOverrides) {
        this.effortOverrides = effortOverrides;
    }

    /**
     * 这个场景这次真正要传的思考档位；null = 不传（用上游默认）。
     *
     * <p>顺序是**面板覆盖值 → 配置默认值**：面板改完立刻生效，不用重启容器。
     */
    public String reasoningEffortFor(LlmScenario scenario) {
        if (effortOverrides != null) {
            String override = effortOverrides.overrideFor(scenario);
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        return reasoningEffort.get(scenario);
    }

    /** 配置文件里的默认档位（不管面板怎么改都不变），给面板显示"默认是什么"用 */
    public String configuredEffortFor(LlmScenario scenario) {
        return reasoningEffort.get(scenario);
    }

    /** 解析 {@code dialog=low,extract=high}；认不出的档位忽略（宁可不传，也不传一个上游不认的值） */
    private static java.util.Map<LlmScenario, String> parseReasoningEffort(String csv) {
        java.util.Map<LlmScenario, String> parsed = new java.util.EnumMap<>(LlmScenario.class);
        if (csv == null || csv.isBlank()) {
            return parsed;
        }
        for (String part : csv.split(",")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0 || eq == item.length() - 1) {
                continue;
            }
            String effort = item.substring(eq + 1).trim().toLowerCase();
            if (!"low".equals(effort) && !"medium".equals(effort) && !"high".equals(effort)) {
                continue;
            }
            parsed.put(LlmScenario.of(item.substring(0, eq).trim()), effort);
        }
        return parsed;
    }

    /**
     * 本次请求的 {@code max_tokens}；**0 = 不设**（保持上游默认）。
     *
     * <p>为什么要这个：早先一个都没设，极端长思考没有任何上限。但注意**思考 token 也算进这个上限**——
     * 实测"每3天"这类问题光思考就 1800+ token，所以结构化场景给的是**宽松的兜底值**（默认 4096），
     * 而对话档默认仍然不设，免得把正常长回复截断。
     */
    public int maxTokensFor(LlmScenario scenario) {
        if (scenario == LlmScenario.DIALOG_DEEP) {
            // 升档是"要更多预算"，所以默认**不设上限**（与普通对话一致）；要限制就显式配 dialog-deep
            return dialogDeepMaxTokens;
        }
        if (scenario == LlmScenario.DIALOG) {
            // 对话档默认不设上限：思考 token 也算进 max_tokens，加了会截断正常长回复
            return dialogMaxTokens;
        }
        if (scenario == LlmScenario.REFLECT) {
            // 反思：输出很短但**思考很重**，所以给比结构化档宽松得多的上限（实测 4096 会被思考吃满）
            return reflectMaxTokens;
        }
        return structuredMaxTokens;
    }

    /** 温度覆写；null = 用全局 {@code llm.temperature} */
    public Double temperatureOverride(LlmScenario scenario) {
        return zeroTemperature.contains(scenario) ? 0.0d : null;
    }

    private static Set<LlmScenario> parseScenarios(String csv, Set<LlmScenario> fallback) {
        if (csv == null || csv.isBlank()) {
            return fallback;
        }
        Set<LlmScenario> parsed = EnumSet.noneOf(LlmScenario.class);
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                parsed.add(LlmScenario.of(part));
            }
        }
        return parsed.isEmpty() ? fallback : parsed;
    }
}
