package com.liche.wechatagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.metrics.RuntimeMetrics;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 非流式 OpenAI 兼容 ChatModel（供记忆提取/提醒解析等一次性 LLM 调用使用） */
public class OpenAiCompatChatModel implements ChatModel {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

    private final RestClient restClient;
    private final String model;
    private final double temperature;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 指标采集，可为 null（单元测试直接构造时不需要） */
    private final RuntimeMetrics metrics;

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature, int timeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS, null);
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature,
                                 int timeoutSeconds, int connectTimeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, connectTimeoutSeconds, null);
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature,
                                 int timeoutSeconds, int connectTimeoutSeconds, RuntimeMetrics metrics) {
        this.model = model;
        this.temperature = temperature;
        this.metrics = metrics;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, Math.min(300, connectTimeoutSeconds))));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, Math.min(600, timeoutSeconds))));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long started = System.nanoTime();
        try {
            String resp = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(OpenAiRequestFactory.buildPayload(model, temperature, request, false).toString())
                    .retrieve()
                    .body(String.class);
            ChatResponse response = parseResponse(objectMapper.readTree(resp));
            record(true, started, null);
            return response;
        } catch (Exception e) {
            record(false, started, e.getMessage());
            throw new RuntimeException("调用 LLM 接口失败: " + e.getMessage(), e);
        }
    }

    private void record(boolean ok, long startedNanos, String error) {
        if (metrics != null) {
            metrics.recordLlm(false, ok, Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L), error);
        }
    }

    private ChatResponse parseResponse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String content = msg.path("content").asText(null);
        List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            toolRequests.add(ToolExecutionRequest.builder()
                    .id(tc.path("id").asText())
                    .name(tc.path("function").path("name").asText())
                    .arguments(tc.path("function").path("arguments").asText())
                    .build());
        }
        AiMessage aiMessage;
        if (toolRequests.isEmpty()) {
            aiMessage = AiMessage.from(content == null ? "" : content);
        } else {
            aiMessage = content == null ? AiMessage.from(toolRequests) : AiMessage.from(content, toolRequests);
        }
        TokenUsage tokenUsage = null;
        JsonNode usage = root.path("usage");
        if (usage.isObject() && !usage.isEmpty()) {
            tokenUsage = new TokenUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        }
        return ChatResponse.builder().aiMessage(aiMessage).tokenUsage(tokenUsage).build();
    }
}
