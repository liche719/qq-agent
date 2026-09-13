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
     * 场景档位：哪些场景关掉"深度思考"、哪些场景用温度 0。
     * 留空的键由 {@link LlmScenarioSettings} 用内置默认值兜底（默认关思考的是 extract / reminder_parse /
     * schedule_parse / archive，对话 dialog 保持模型默认档）。
     */
    @Bean
    public LlmScenarioSettings llmScenarioSettings(
            @Value("${llm.thinking.disabled-scenarios:}") String disabledScenarios,
            @Value("${llm.thinking.enabled-scenarios:}") String enabledScenarios,
            @Value("${llm.thinking.disabled-body:}") String disabledBody,
            @Value("${llm.thinking.enabled-body:}") String enabledBody,
            @Value("${llm.zero-temperature-scenarios:}") String zeroTemperatureScenarios) {
        return new LlmScenarioSettings(disabledScenarios, enabledScenarios, disabledBody, enabledBody,
                zeroTemperatureScenarios);
    }

    /** 非流式模型：记忆提取 / 提醒解析 / 归档摘要等一次性调用 */
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
