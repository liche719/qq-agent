package com.liche.wechatagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.metrics.RuntimeMetrics;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 流式 OpenAI 兼容 StreamingChatModel（SSE 逐段解析，用于对话回复的「打字机」体验） */
public class OpenAiCompatStreamingChatModel implements StreamingChatModel {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatStreamingChatModel.class);

    private final OkHttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 场景档位（关思考 / 温度）；可为 null（单测直接构造时不需要） */
    private final LlmScenarioSettings scenarioSettings;
    /** 指标采集，可为 null（单元测试直接构造时不需要） */
    private final RuntimeMetrics metrics;

    public OpenAiCompatStreamingChatModel(String baseUrl, String apiKey, String model,
                                          double temperature, int timeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS, null);
    }

    public OpenAiCompatStreamingChatModel(String baseUrl, String apiKey, String model,
                                          double temperature, int timeoutSeconds, int connectTimeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, connectTimeoutSeconds, null);
    }

    public OpenAiCompatStreamingChatModel(String baseUrl, String apiKey, String model, double temperature,
                                          int timeoutSeconds, int connectTimeoutSeconds, RuntimeMetrics metrics) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, connectTimeoutSeconds, metrics, null);
    }

    public OpenAiCompatStreamingChatModel(String baseUrl, String apiKey, String model, double temperature,
                                          int timeoutSeconds, int connectTimeoutSeconds, RuntimeMetrics metrics,
                                          LlmScenarioSettings scenarioSettings) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.scenarioSettings = scenarioSettings;
        this.metrics = metrics;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(1, Math.min(300, connectTimeoutSeconds)), TimeUnit.SECONDS)
                .readTimeout(Math.max(1, Math.min(600, timeoutSeconds)), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        long started = System.nanoTime();
        LlmScenario scenario = LlmScenario.current();
        double effectiveTemperature = temperatureFor(scenario);
        JsonNode extraBody = scenarioSettings == null ? null : scenarioSettings.extraBody(scenario);
        int reasoningChars = 0;
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                            OpenAiRequestFactory.buildPayload(model, effectiveTemperature, request, true, extraBody)
                                    .toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response response = client.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    record(false, started, "HTTP " + response.code(), scenario, effectiveTemperature, 0, 0);
                    handler.onError(new RuntimeException("LLM 流式请求失败 HTTP " + response.code()));
                    return;
                }
                StringBuilder content = new StringBuilder();
                Map<Integer, StringBuilder> toolArgs = new LinkedHashMap<>();
                Map<Integer, String> toolNames = new LinkedHashMap<>();
                Map<Integer, String> toolIds = new LinkedHashMap<>();
                List<JsonNode> rawToolCalls = new ArrayList<>();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        response.body().byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) {
                            continue;
                        }
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        JsonNode node = objectMapper.readTree(data);
                        JsonNode delta = node.path("choices").path(0).path("delta");
                        // 这个模型默认就带思考：思考走 delta.reasoning_content（不推给用户，只用来记账）
                        JsonNode reasoningDelta = delta.path("reasoning_content");
                        if (reasoningDelta.isTextual()) {
                            reasoningChars += reasoningDelta.asText().length();
                        }
                        String partial = delta.path("content").asText(null);
                        if (partial != null && !partial.isEmpty()) {
                            content.append(partial);
                            handler.onPartialResponse(partial);
                        }
                        for (JsonNode tc : delta.path("tool_calls")) {
                            int idx = tc.path("index").asInt(0);
                            if (tc.path("id").isTextual()) {
                                toolIds.put(idx, tc.path("id").asText());
                            }
                            if (tc.path("function").path("name").isTextual()) {
                                toolNames.put(idx, tc.path("function").path("name").asText());
                            }
                            String argFrag = tc.path("function").path("arguments").asText("");
                            if (!argFrag.isEmpty()) {
                                toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argFrag);
                            }
                            rawToolCalls.add(tc);
                        }
                    }
                }

                List<ToolExecutionRequest> toolRequests = new ArrayList<>();
                for (int idx : toolArgs.keySet()) {
                    toolRequests.add(ToolExecutionRequest.builder()
                            .id(toolIds.getOrDefault(idx, "call_" + idx))
                            .name(toolNames.getOrDefault(idx, ""))
                            .arguments(toolArgs.get(idx).toString())
                            .build());
                }
                if (toolRequests.isEmpty() && !rawToolCalls.isEmpty()) {
                    for (JsonNode tc : rawToolCalls) {
                        String name = tc.path("function").path("name").asText("");
                        if (!name.isBlank()) {
                            toolRequests.add(ToolExecutionRequest.builder()
                                    .id(tc.path("id").asText("call"))
                                    .name(name)
                                    .arguments(tc.path("function").path("arguments").asText(""))
                                    .build());
                        }
                    }
                }

                String fullText = content.toString();
                AiMessage aiMessage = toolRequests.isEmpty()
                        ? AiMessage.from(fullText)
                        : (fullText.isEmpty() ? AiMessage.from(toolRequests) : AiMessage.from(fullText, toolRequests));
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(aiMessage).build());
                record(true, started, null, scenario, effectiveTemperature, reasoningChars, fullText.length());
            }
        } catch (Exception e) {
            record(false, started, e.getMessage(), scenario, effectiveTemperature, reasoningChars, 0);
            handler.onError(e);
        }
    }

    private double temperatureFor(LlmScenario scenario) {
        Double override = scenarioSettings == null ? null : scenarioSettings.temperatureOverride(scenario);
        return override == null ? temperature : override;
    }

    private String thinkingLabel(LlmScenario scenario) {
        return scenarioSettings == null ? "default" : scenarioSettings.thinkingLabel(scenario);
    }

    private void record(boolean ok, long startedNanos, String error, LlmScenario scenario,
                        double effectiveTemperature, int reasoningChars, int contentChars) {
        long millis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        if (metrics != null) {
            // 流式响应没有 usage（除非开 stream_options），所以 token 记 0，思考量用字符数代替
            metrics.recordLlm(true, ok, millis, error, scenario.label(), 0, 0, 0);
        }
        if (ok) {
            log.info("LLM 流式调用 scenario={} ms={} temperature={} thinking={} 正文={}字 思考={}字",
                    scenario.label(), millis, effectiveTemperature, thinkingLabel(scenario), contentChars,
                    reasoningChars);
        } else {
            log.warn("LLM 流式调用失败 scenario={} ms={} thinking={} error={}", scenario.label(), millis,
                    thinkingLabel(scenario), error);
        }
    }
}
