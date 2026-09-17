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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 非流式 OpenAI 兼容 ChatModel（供记忆提取/提醒解析等一次性 LLM 调用使用） */
public class OpenAiCompatChatModel implements ChatModel {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatChatModel.class);

    private final RestClient restClient;
    private final String model;
    private final double temperature;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 场景档位（关思考 / 温度）；可为 null（单测直接构造时不需要） */
    private final LlmScenarioSettings scenarioSettings;
    /** 指标采集，可为 null（单元测试直接构造时不需要） */
    private final RuntimeMetrics metrics;
    /** 用量接收端（记账/审计/熔断）；Spring 注入，单测直接构造时为空 */
    private List<LlmUsageSink> usageSinks = List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsageSinks(List<LlmUsageSink> usageSinks) {
        this.usageSinks = usageSinks == null ? List.of() : usageSinks;
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature, int timeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS, null);
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature,
                                 int timeoutSeconds, int connectTimeoutSeconds) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, connectTimeoutSeconds, null);
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature,
                                 int timeoutSeconds, int connectTimeoutSeconds, RuntimeMetrics metrics) {
        this(baseUrl, apiKey, model, temperature, timeoutSeconds, connectTimeoutSeconds, metrics, null);
    }

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String model, double temperature,
                                 int timeoutSeconds, int connectTimeoutSeconds, RuntimeMetrics metrics,
                                 LlmScenarioSettings scenarioSettings) {
        this.model = model;
        this.temperature = temperature;
        this.scenarioSettings = scenarioSettings;
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
        // 模型可以用 thinkDeeper 申请升档：升档后按 DIALOG_DEEP 取设置（更宽松的 max_tokens）
        LlmScenario scenario = LlmEscalation.effective(LlmScenario.current());
        double effectiveTemperature = temperatureFor(scenario);
        int maxTokens = scenarioSettings == null ? 0 : scenarioSettings.maxTokensFor(scenario);
        try {
            String resp = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(OpenAiRequestFactory.buildPayload(model, effectiveTemperature, request, false,
                            maxTokens).toString())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(resp);
            ChatResponse response = parseResponse(root);
            record(true, started, null, scenario, effectiveTemperature, maxTokens, usageOf(root));
            return response;
        } catch (Exception e) {
            record(false, started, e.getMessage(), scenario, effectiveTemperature, maxTokens, Usage.EMPTY);
            throw new RuntimeException("调用 LLM 接口失败: " + e.getMessage(), e);
        }
    }

    private double temperatureFor(LlmScenario scenario) {
        Double override = scenarioSettings == null ? null : scenarioSettings.temperatureOverride(scenario);
        return override == null ? temperature : override;
    }

    private void record(boolean ok, long startedNanos, String error, LlmScenario scenario,
                        double effectiveTemperature, int maxTokens, Usage usage) {
        long millis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        if (metrics != null) {
            metrics.recordLlm(false, ok, millis, error, scenario.label(), usage.prompt(), usage.completion(),
                    usage.reasoning());
        }
        if (ok) {
            publishUsage(usage);
            log.info("LLM 调用 scenario={} ms={} temperature={} maxTokens={} promptTokens={} "
                            + "completionTokens={} reasoningTokens={}",
                    scenario.label(), millis, effectiveTemperature, maxTokens,
                    usage.prompt(), usage.completion(), usage.reasoning());
        } else {
            log.warn("LLM 调用失败 scenario={} ms={} error={}", scenario.label(), millis, error);
        }
    }

    /** usage 里的 token 数：reasoning 已经含在 completion 里，单独记只是为了看清"思考花了多少" */
    private record Usage(int prompt, int completion, int reasoning, int cached) {
        private static final Usage EMPTY = new Usage(0, 0, 0, 0);
    }

    private Usage usageOf(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.isObject() || usage.isEmpty()) {
            return Usage.EMPTY;
        }
        // 缓存命中：标准字段优先、厂商字段回退（与流式模型同一套规则）
        int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(-1);
        if (cached < 0) {
            cached = usage.path("prompt_cache_hit_tokens").asInt(0);
        }
        return new Usage(usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0),
                Math.max(0, cached));
    }

    /**
     * 把这一跳的用量广播给接收端（记账、熔断、审计）。
     *
     * <p>**2026-09-17 补**：原先只有流式模型会广播，非流式这条（提取/反思/提醒解析/排程解析/归档）
     * 的用量**根本没进账本**——所以"今天花了多少"一直少算这几类。现在两边一致。
     * 字段名映射只在这里做，谁拿它做什么由 {@link LlmUsageSink} 的实现决定。
     */
    private void publishUsage(Usage usage) {
        if (usageSinks.isEmpty() || usage == null) {
            return;
        }
        LlmUsage payload = new LlmUsage(Math.max(0, usage.prompt()), Math.max(0, usage.completion()),
                Math.max(0, usage.reasoning()), Math.max(0, usage.cached()));
        for (LlmUsageSink sink : usageSinks) {
            try {
                sink.accept(payload);
            } catch (RuntimeException exception) {
                // 记账失败不能把模型调用带崩（约定见 LlmUsageSink）
                log.warn("用量接收端出错（已忽略）：{}", exception.getMessage());
            }
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
