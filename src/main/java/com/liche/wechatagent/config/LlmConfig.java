package com.liche.wechatagent.config;

import com.liche.wechatagent.metrics.RuntimeMetrics;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /**
     * 场景档位：哪些场景用温度 0、每个场景的 {@code max_tokens} 上限、每个场景的**思考档位**。
     * 留空的键由 {@link LlmScenarioSettings} 用内置默认值兜底（默认温度 0 的是四个结构化场景）。
     *
     * <p>思考档位（{@code reasoning_effort}）2026-09-18 加：用户要求"对话 low、提取 high"。
     * 实测 low 的思考 token 只有默认的 1/3、耗时 4.8s vs 14s，所以对话这条最影响体感的路径先降下来。
     */
    @Bean
    public LlmScenarioSettings llmScenarioSettings(
            @Value("${llm.zero-temperature-scenarios:}") String zeroTemperatureScenarios,
            @Value("${llm.max-tokens.structured:16384}") int structuredMaxTokens,
            @Value("${llm.max-tokens.dialog:0}") int dialogMaxTokens,
            @Value("${llm.max-tokens.reflect:16384}") int reflectMaxTokens,
            @Value("${llm.reasoning-effort:dialog=low,extract=high}") String reasoningEffortScenarios) {
        return new LlmScenarioSettings(zeroTemperatureScenarios, structuredMaxTokens, dialogMaxTokens,
                reflectMaxTokens, reasoningEffortScenarios);
    }

    /** 非流式模型：记忆提取 / 反思 / 提醒解析 / 排程解析等一次性调用 */
    @Bean
    public ChatModel chatModel(@Value("${llm.base-url}") String baseUrl,
                               @Value("${llm.api-key}") String apiKey,
                               @Value("${llm.model}") String model,
                               @Value("${llm.temperature:0.7}") double temperature,
                               @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
                               @Value("${llm.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                               RuntimeMetrics metrics,
                               LlmScenarioSettings scenarioSettings) {
        return new OpenAiCompatChatModel(baseUrl, apiKey, model, temperature, timeoutSeconds,
                connectTimeoutSeconds, metrics, scenarioSettings);
    }

    /** 流式模型：对话回复（打字机效果） */
    @Bean
    public StreamingChatModel streamingChatModel(@Value("${llm.base-url}") String baseUrl,
                                                 @Value("${llm.api-key}") String apiKey,
                                                 @Value("${llm.model}") String model,
                                                 @Value("${llm.temperature:0.7}") double temperature,
                                                 @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
                                                 @Value("${llm.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                                                 RuntimeMetrics metrics,
                                                 LlmScenarioSettings scenarioSettings) {
        return new OpenAiCompatStreamingChatModel(baseUrl, apiKey, model, temperature, timeoutSeconds,
                connectTimeoutSeconds, metrics, scenarioSettings);
    }
}
