package com.liche.wechatagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /** 非流式模型：记忆提取 / 提醒解析 / 归档摘要等一次性调用 */
    @Bean
    public ChatModel chatModel(@Value("${llm.base-url}") String baseUrl,
                               @Value("${llm.api-key}") String apiKey,
                               @Value("${llm.model}") String model,
                               @Value("${llm.temperature:0.7}") double temperature,
                               @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
                               @Value("${llm.connect-timeout-seconds:5}") int connectTimeoutSeconds) {
        return new OpenAiCompatChatModel(baseUrl, apiKey, model, temperature, timeoutSeconds,
                connectTimeoutSeconds);
    }

    /** 流式模型：对话回复（打字机效果） */
    @Bean
    public StreamingChatModel streamingChatModel(@Value("${llm.base-url}") String baseUrl,
                                                 @Value("${llm.api-key}") String apiKey,
                                                 @Value("${llm.model}") String model,
                                                 @Value("${llm.temperature:0.7}") double temperature,
                                                 @Value("${llm.timeout-seconds:60}") int timeoutSeconds,
                                                 @Value("${llm.connect-timeout-seconds:5}") int connectTimeoutSeconds) {
        return new OpenAiCompatStreamingChatModel(baseUrl, apiKey, model, temperature, timeoutSeconds,
                connectTimeoutSeconds);
    }
}
