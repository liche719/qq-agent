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
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 用量接收端（setter 注入，单测直接 new 时为空——那时不记账）。**模型层不认识钱**。 */
    private List<LlmUsageSink> usageSinks = List.of();

    @Autowired(required = false)
    public void setUsageSinks(List<LlmUsageSink> usageSinks) {
        this.usageSinks = usageSinks == null ? List.of() : usageSinks;
    }

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
        LlmScenario scenario = LlmEscalation.effective(LlmScenario.current());
        double effectiveTemperature = temperatureFor(scenario);
        int maxTokens = scenarioSettings == null ? 0 : scenarioSettings.maxTokensFor(scenario);
        String reasoningEffort = scenarioSettings == null ? null : scenarioSettings.reasoningEffortFor(scenario);
        int reasoningChars = 0;
        // 实测（2026-09-14）：这个接口的流式响应**本来就带 usage**（不需要 stream_options.include_usage），
        // 所以对话这几档的 token 也能照实记账，面板「按场景」表格不再恒为 0。取最后一个非空 usage。
        int[] tokens = new int[3]; // prompt / completion / reasoning
        int[] cachedTokens = new int[1]; // 缓存命中的输入（计价关键；服务端没给就是 0）
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                            OpenAiRequestFactory.buildPayload(model, effectiveTemperature, request, true,
                                    maxTokens, reasoningEffort).toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response response = client.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    // 原来只记一句 "HTTP 400"，服务端说的原因被整个丢掉——排"问时间就 400"那次
                    // 只能靠猜。把响应体打出来，以后所有 4xx/5xx 都能一眼看到上游怎么说。
                    String detail = "";
                    try {
                        detail = response.body() == null ? "" : response.body().string();
                    } catch (Exception ignored) {
                        // 读不出来就算了，不能因为记日志把请求本身弄挂
                    }
                    if (detail.length() > 800) {
                        detail = detail.substring(0, 800) + "…";
                    }
                    log.warn("LLM 流式请求被拒 HTTP {} body={}", response.code(), detail);
                    record(false, started, "HTTP " + response.code(), scenario, effectiveTemperature, maxTokens, 0, 0,
                            tokens, reasoningEffort);
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
                        JsonNode usageNode = node.path("usage");
                        if (usageNode.isObject() && !usageNode.isEmpty()) {
                            tokens[0] = usageNode.path("prompt_tokens").asInt(tokens[0]);
                            tokens[1] = usageNode.path("completion_tokens").asInt(tokens[1]);
                            tokens[2] = usageNode.path("completion_tokens_details").path("reasoning_tokens")
                                    .asInt(tokens[2]);
                            // 缓存命中的输入：**优先 OpenAI 标准的嵌套字段**，回退 DeepSeek 的平铺字段。
                            // 两个都没有就是 0——记账方按"全部未命中"算（宁高估不低估）。
                            JsonNode details = usageNode.path("prompt_tokens_details");
                            if (details.isObject() && details.has("cached_tokens")) {
                                cachedTokens[0] = details.path("cached_tokens").asInt(0);
                            } else {
                                cachedTokens[0] = usageNode.path("prompt_cache_hit_tokens")
                                        .asInt(cachedTokens[0]);
                            }
                        }
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
                // usage 已经解析出来了（见上面的实测说明），顺手塞进 ChatResponse：
                // 否则只有 metrics 拿得到 token，调用链/诊断视图只能显示 0（2026-09-15）
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(aiMessage)
                        .tokenUsage(new TokenUsage(tokens[0], tokens[1])).build());
                publishUsage(cachedTokens, tokens);
                record(true, started, null, scenario, effectiveTemperature, maxTokens, reasoningChars, fullText.length(),
                        tokens, reasoningEffort);
            }
        } catch (Exception e) {
            record(false, started, e.getMessage(), scenario, effectiveTemperature, maxTokens, reasoningChars, 0, tokens,
                    reasoningEffort);
            handler.onError(e);
        }
    }

    private double temperatureFor(LlmScenario scenario) {
        Double override = scenarioSettings == null ? null : scenarioSettings.temperatureOverride(scenario);
        return override == null ? temperature : override;
    }

    /**
     * 把这一次的用量交给所有接收端（2026-09-15 解耦后：**模型层不认识钱**）。
     *
     * <p>字段名映射只在这里做（标准字段优先、厂商字段回退），谁要拿它做什么
     * （记账、按钱熔断、写审计）由 {@link LlmUsageSink} 的实现决定。换模型实现时，
     * 只要它也把用量归一化后广播出去，计费一行都不用改。
     */
    private void publishUsage(int[] cachedTokens, int[] tokens) {
        if (usageSinks.isEmpty()) {
            return;
        }
        LlmUsage usage = new LlmUsage(Math.max(0, tokens[0]), Math.max(0, tokens[1]),
                Math.max(0, tokens[2]), Math.max(0, cachedTokens[0]));
        for (LlmUsageSink sink : usageSinks) {
            try {
                sink.accept(usage);
            } catch (RuntimeException exception) {
                // 记账失败不能把模型调用带崩（约定见 LlmUsageSink）
                log.warn("用量接收端出错（已忽略）：{}", exception.getMessage());
            }
        }
    }

    private void record(boolean ok, long startedNanos, String error, LlmScenario scenario,
                        double effectiveTemperature, int maxTokens, int reasoningChars, int contentChars,
                        int[] tokens, String reasoningEffort) {
        long millis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        if (metrics != null) {
            // usage 直接来自流式响应（见 chat 里的实测说明）；拿不到时是 0，日志里另有字符数兜底
            metrics.recordLlm(true, ok, millis, error, scenario.label(), tokens[0], tokens[1], tokens[2]);
        }
        if (ok) {
            log.info("LLM 流式调用 scenario={} ms={} temperature={} maxTokens={} reasoningEffort={}"
                            + " 正文={}字 思考={}字 promptTokens={} completionTokens={} reasoningTokens={}",
                    scenario.label(), millis, effectiveTemperature, maxTokens,
                    reasoningEffort == null ? "(默认)" : reasoningEffort,
                    contentChars, reasoningChars, tokens[0], tokens[1], tokens[2]);
        } else {
            log.warn("LLM 流式调用失败 scenario={} ms={} error={}", scenario.label(), millis, error);
        }
    }
}
