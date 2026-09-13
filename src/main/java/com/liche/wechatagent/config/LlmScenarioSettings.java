package com.liche.wechatagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.Set;

/**
 * 每个调用场景的「档位」：要不要深度思考、温度多少。
 *
 * <p>字段形状放在配置里（{@code llm.thinking.disabled-body}），代码不写死——上游换一种开关写法只要改配置。
 *
 * <p>已实测（2026-09-13，{@code deepseek-v4-flash-vision-exp} + api.deepseek.com）：
 * <ul>
 *   <li>这个模型**默认就在思考**（不带任何参数也会返回 reasoning_content，思考 token 占了输出的大头）；</li>
 *   <li>唯一有效的关闭方式是 {@code thinking:{"type":"disabled"}}；</li>
 *   <li>{@code enable_thinking:false} 与 {@code chat_template_kwargs.thinking=false} 会被**静默忽略**；</li>
 *   <li>{@code reasoning_effort:"none"} 也能关，但在极简问题上出现过只输出 1 个 token 的退化，不用它。</li>
 * </ul>
 */
public class LlmScenarioSettings {

    /**
     * 默认档：**只关实测过、且失败代价小**的两个场景。
     *
     * <ul>
     *   <li>{@code extract}：每轮对话都会跑，输出是结构化事实，实测同一条消息开/关思考提取结果一致
     *       （episodes/work/core 数量相同），而 8194ms/1516token → 2254ms/493token；</li>
     *   <li>{@code schedule_parse}：自然语言 → cron，实测 8/8 完全正确，且 {@code isValidCron} 有兜底，
     *       真解析错了也只是反问用户；</li>
     * </ul>
     *
     * <p>**reminder_parse 与 archive 不关**：前者是提醒时间，解析错了用户会漏掉提醒（且调用很少，
     * 省下的 token 可以忽略）；后者是长期记忆的摘要，质量优先（第一优先级是"记忆不丢失"）。
     */
    private static final Set<LlmScenario> DEFAULT_OFF = EnumSet.of(
            LlmScenario.EXTRACT, LlmScenario.SCHEDULE_PARSE, LlmScenario.DIALOG_FAST);
    /** 默认温度 0 的场景：只要结构化输出正确，不需要发散。**不要复用 DEFAULT_OFF**：省电档应该还是 0.7 */
    private static final Set<LlmScenario> DEFAULT_ZERO_TEMPERATURE = EnumSet.of(
            LlmScenario.EXTRACT, LlmScenario.REMINDER_PARSE, LlmScenario.SCHEDULE_PARSE, LlmScenario.ARCHIVE);

    /** 显式打开思考的场景：升档后要"确保开着"，不依赖上游默认 */
    private static final Set<LlmScenario> DEFAULT_ON = EnumSet.of(LlmScenario.DIALOG_DEEP);
    private static final String DEFAULT_OFF_BODY = "{\"thinking\":{\"type\":\"disabled\"}}";
    private static final String DEFAULT_ON_BODY = "{\"thinking\":{\"type\":\"enabled\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<LlmScenario> thinkingOff;
    private final Set<LlmScenario> thinkingOn;
    private final Set<LlmScenario> zeroTemperature;
    private final JsonNode offBody;
    private final JsonNode onBody;
    private final int structuredMaxTokens;
    private final int dialogMaxTokens;
    private final int dialogDeepMaxTokens;

    public LlmScenarioSettings(String disabledScenarios, String enabledScenarios, String disabledBody,
                               String enabledBody, String zeroTemperatureScenarios,
                               int structuredMaxTokens, int dialogMaxTokens, int dialogDeepMaxTokens) {
        this.structuredMaxTokens = Math.max(0, structuredMaxTokens);
        this.dialogMaxTokens = Math.max(0, dialogMaxTokens);
        this.dialogDeepMaxTokens = Math.max(0, dialogDeepMaxTokens);
        this.thinkingOff = parseScenarios(disabledScenarios, DEFAULT_OFF);
        this.thinkingOn = parseScenarios(enabledScenarios, DEFAULT_ON);
        this.zeroTemperature = parseScenarios(zeroTemperatureScenarios, DEFAULT_ZERO_TEMPERATURE);
        this.offBody = readJson(disabledBody, DEFAULT_OFF_BODY);
        this.onBody = readJson(enabledBody, DEFAULT_ON_BODY);
    }

    public boolean thinkingDisabled(LlmScenario scenario) {
        return thinkingOff.contains(scenario);
    }

    /** 这次请求要额外塞进请求体的字段；null = 一个字段都不加（保持上游默认行为） */
    public JsonNode extraBody(LlmScenario scenario) {
        if (thinkingOff.contains(scenario)) {
            return offBody;
        }
        if (thinkingOn.contains(scenario)) {
            return onBody;
        }
        return null;
    }

    /**
     * 本次请求的 {@code max_tokens}；**0 = 不设**（保持上游默认）。
     *
     * <p>为什么要这个：之前一个都没设，极端长思考没有任何上限。但注意**思考 token 也算进这个上限**——
     * 实测"每3天"这类问题光思考就 1800+ token，所以结构化场景给的是**宽松的兜底值**（默认 4096），
     * 而对话档默认仍然不设，免得把正常长回复截断。
     */
    public int maxTokensFor(LlmScenario scenario) {
        if (scenario == LlmScenario.DIALOG_DEEP) {
            // 升档是"要更多预算"，所以默认**不设上限**（与普通对话一致）；要限制就显式配 dialog-deep
            return dialogDeepMaxTokens;
        }
        if (scenario == LlmScenario.DIALOG || scenario == LlmScenario.DIALOG_FAST) {
            // 对话档（含省电档）默认不设上限：思考 token 也算进 max_tokens，加了会截断正常长回复
            return dialogMaxTokens;
        }
        return structuredMaxTokens;
    }

    /** 温度覆写；null = 用全局 {@code llm.temperature} */
    public Double temperatureOverride(LlmScenario scenario) {
        return zeroTemperature.contains(scenario) ? 0.0d : null;
    }

    /** 日志用：off / on / default */
    public String thinkingLabel(LlmScenario scenario) {
        if (thinkingOff.contains(scenario)) {
            return "off";
        }
        return thinkingOn.contains(scenario) ? "on" : "default";
    }

    /** 自检用：当前关掉思考的场景 */
    public Set<LlmScenario> thinkingDisabledScenarios() {
        return Set.copyOf(thinkingOff);
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

    private JsonNode readJson(String text, String fallback) {
        String value = text == null || text.isBlank() ? fallback : text;
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            // 配错了就当没配：宁可走上游默认，也不要发一个坏请求体出去
            return null;
        }
    }
}
