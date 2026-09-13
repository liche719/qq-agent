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

    /** 默认档：结构性任务关思考 + 温度 0（实测输出 token 少约 90%、快约 2.4 倍，JSON 依然合法） */
    private static final Set<LlmScenario> DEFAULT_OFF = EnumSet.of(
            LlmScenario.EXTRACT, LlmScenario.REMINDER_PARSE, LlmScenario.SCHEDULE_PARSE, LlmScenario.ARCHIVE);
    private static final String DEFAULT_OFF_BODY = "{\"thinking\":{\"type\":\"disabled\"}}";
    private static final String DEFAULT_ON_BODY = "{\"thinking\":{\"type\":\"enabled\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<LlmScenario> thinkingOff;
    private final Set<LlmScenario> thinkingOn;
    private final Set<LlmScenario> zeroTemperature;
    private final JsonNode offBody;
    private final JsonNode onBody;

    public LlmScenarioSettings(String disabledScenarios, String enabledScenarios, String disabledBody,
                               String enabledBody, String zeroTemperatureScenarios) {
        this.thinkingOff = parseScenarios(disabledScenarios, DEFAULT_OFF);
        this.thinkingOn = parseScenarios(enabledScenarios, EnumSet.noneOf(LlmScenario.class));
        this.zeroTemperature = parseScenarios(zeroTemperatureScenarios, DEFAULT_OFF);
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
