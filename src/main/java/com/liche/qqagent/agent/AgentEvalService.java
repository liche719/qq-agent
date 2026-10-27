package com.liche.qqagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.qqagent.config.LlmScenario;
import com.liche.qqagent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 行为评估（2026-10-27，P1-1 的扩展）。
 *
 * <p>记忆评估（{@code MemoryEvalService}）只能证明"记得住、取得到"，证明不了
 * **"该调工具的时候会不会调、该拒绝的时候会不会瞎调"**。这里用同一套思路补上：
 *
 * <ul>
 *   <li>{@code choice}：给一句话 + 完整工具清单，看模型的**第一反应**调不调、调哪个工具。
 *       **只读决策、真不执行**——不触碰任何副作用；</li>
 *   <li>{@code continuity}：给一段多轮历史 + 追问，看回答里有没有引用正确的旧信息。</li>
 * </ul>
 *
 * <p>每条用例重复 {@code repeat} 次（默认 3），报**通过次数**而不是单次正负——
 * 小样本单次结果会被抖动骗（见 docs/plan-2026-10-26.md 补充调研 #14/#30）。
 */
@Service
public class AgentEvalService {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final boolean enabled;
    private final int repeat;

    public AgentEvalService(ChatModel chatModel,
                            ToolRegistry toolRegistry,
                            @org.springframework.beans.factory.annotation.Value("${agent.eval-enabled:false}")
                            boolean enabled,
                            @org.springframework.beans.factory.annotation.Value("${agent.eval-repeat:3}")
                            int repeat) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.enabled = enabled;
        this.repeat = Math.max(1, Math.min(5, repeat));
    }

    public boolean enabled() {
        return enabled;
    }

    /** 跑全部 agent 行为用例 */
    public Map<String, Object> run(String userId) {
        if (!enabled) {
            return Map.of("ok", false, "error", "评估开关关着（agent.eval-enabled=false）");
        }
        List<JsonNode> cases = loadCases();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (JsonNode node : cases) {
            String type = node.path("type").asText("choice");
            Map<String, Object> one = "continuity".equals(type)
                    ? runContinuity(node) : runChoice(node);
            if (Boolean.TRUE.equals(one.get("pass"))) {
                passed++;
            }
            results.add(one);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("suite", "agent");
        report.put("total", cases.size());
        report.put("passed", passed);
        report.put("score", cases.isEmpty() ? "0%" : (passed * 100 / cases.size()) + "%");
        report.put("repeat", repeat);
        report.put("results", results);
        log.info("Agent 行为评估完成 {}/{}（每条重复 {} 次）", passed, cases.size(), repeat);
        return report;
    }

    /** 工具选择：只读决策，不执行 */
    private Map<String, Object> runChoice(JsonNode node) {
        String id = node.path("id").asText("");
        String prompt = node.path("prompt").asText("");
        String expect = node.path("expectTool").asText("");
        List<String> expectAny = new ArrayList<>();
        for (JsonNode alt : node.path("expectAnyTool")) {
            String value = alt.asText("").trim();
            if (!value.isBlank()) {
                expectAny.add(value);
            }
        }
        String forbid = node.path("forbidTool").asText("");
        int pass = 0;
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < repeat; i++) {
            List<String> names = askToolNames(prompt);
            calls.add(names.isEmpty() ? "-" : String.join("+", names));
            boolean ok;
            if (!expectAny.isEmpty()) {
                ok = names.stream().anyMatch(expectAny::contains);
            } else if (!expect.isBlank()) {
                ok = names.contains(expect);
            } else {
                // 期望"不调工具"或"别调某个工具"
                ok = forbid.isBlank() ? names.isEmpty() : !names.contains(forbid);
            }
            if (ok) {
                pass++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", "choice");
        result.put("expect", !expectAny.isEmpty() ? String.join(" 或 ", expectAny)
                : (expect.isBlank() ? (forbid.isBlank() ? "不调工具" : "不调 " + forbid) : expect));
        result.put("observed", calls);
        result.put("passCount", pass);
        result.put("pass", pass * 2 > repeat);   // 过半算通过
        return result;
    }

    /** 多轮连续性：给历史 + 追问，看回答是否引用旧信息 */
    private Map<String, Object> runContinuity(JsonNode node) {
        String id = node.path("id").asText("");
        String expect = node.path("expectContains").asText("");
        List<String> expectAny = new ArrayList<>();
        for (JsonNode alt : node.path("expectAnyContains")) {
            String value = alt.asText("").trim();
            if (!value.isBlank()) {
                expectAny.add(value);
            }
        }
        List<ChatMessage> base = new ArrayList<>();
        base.add(SystemMessage.from("你是一个中文个人助手。回答要简短，直接回答问题。"));
        for (JsonNode turn : node.path("history")) {
            String role = turn.path("role").asText("user");
            String text = turn.path("text").asText("");
            base.add("assistant".equals(role) ? AiMessage.from(text) : UserMessage.from(text));
        }
        String question = node.path("question").asText("");
        int pass = 0;
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < repeat; i++) {
            List<ChatMessage> messages = new ArrayList<>(base);
            messages.add(UserMessage.from(question));
            String answer = ask(messages, List.of());
            answers.add(clip(answer, 60));
            String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
            boolean ok = !expectAny.isEmpty()
                    ? expectAny.stream().anyMatch(value -> lower.contains(value.toLowerCase(Locale.ROOT)))
                    : lower.contains(expect.toLowerCase(Locale.ROOT));
            if (ok) {
                pass++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", "continuity");
        result.put("expect", expect);
        result.put("observed", answers);
        result.put("passCount", pass);
        result.put("pass", pass * 2 > repeat);
        return result;
    }

    /** 问一句，返回模型第一反应里要调的工具名 */
    private List<String> askToolNames(String prompt) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个中文个人助手，必要时调用工具，不需要就不要调。"),
                UserMessage.from(prompt));
        List<ToolSpecification> specs = toolRegistry.specificationsOf(null);
        ChatResponse response = askRaw(messages, specs);
        if (response == null || response.aiMessage() == null
                || !response.aiMessage().hasToolExecutionRequests()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        response.aiMessage().toolExecutionRequests().forEach(request -> names.add(request.name()));
        return names;
    }

    private String ask(List<ChatMessage> messages, List<ToolSpecification> specs) {
        ChatResponse response = askRaw(messages, specs);
        if (response == null || response.aiMessage() == null) {
            return "";
        }
        String text = response.aiMessage().text();
        return text == null ? "" : text;
    }

    /** 两次调用之间的间隔：上游有 30 次/秒的限流，评估连发会撞 429（2026-10-27 实测） */
    @org.springframework.beans.factory.annotation.Value("${agent.eval-call-gap-ms:600}")
    private long callGapMs = 600;

    private ChatResponse askRaw(List<ChatMessage> messages, List<ToolSpecification> specs) {
        ChatResponse response = askRawOnce(messages, specs, 0);
        if (response != null) {
            pause();
        }
        return response;
    }

    private ChatResponse askRawOnce(List<ChatMessage> messages, List<ToolSpecification> specs, int attempt) {
        try {
            ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
            if (specs != null && !specs.isEmpty()) {
                builder.toolSpecifications(specs);
            }
            ChatRequest request = builder.build();
            return LlmScenario.run(LlmScenario.DIALOG, () -> chatModel.chat(request));
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (attempt < 2 && (message.contains("429") || message.contains("RATE_LIMITED"))) {
                long waitMs = retryAfterMillis(message);
                log.info("评估撞上限流，等 {} ms 后重试（第 {} 次）", waitMs, attempt + 1);
                sleepQuietly(waitMs);
                return askRawOnce(messages, specs, attempt + 1);
            }
            log.warn("评估调用失败：{}", message);
            return null;
        }
    }

    private void pause() {
        if (callGapMs > 0) {
            sleepQuietly(callGapMs);
        }
    }

    private static long retryAfterMillis(String message) {
        // 不用正则：从 "retryAfterSeconds":8 里直接抠数字（正则的反斜杠在多层转义里太容易写错）
        int at = message.indexOf("retryAfterSeconds");
        if (at < 0) {
            return 8000L;
        }
        int i = at + "retryAfterSeconds".length();
        while (i < message.length() && !Character.isDigit(message.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < message.length() && Character.isDigit(message.charAt(i))) {
            i++;
        }
        if (i == start) {
            return 8000L;
        }
        try {
            return Math.max(1000L, Long.parseLong(message.substring(start, i)) * 1000L + 500L);
        } catch (NumberFormatException exception) {
            return 8000L;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<JsonNode> loadCases() {
        List<JsonNode> cases = new ArrayList<>();
        try (InputStream in = new ClassPathResource("eval/agent-cases.json").getInputStream()) {
            JsonNode root = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            root.path("cases").forEach(cases::add);
        } catch (Exception exception) {
            log.warn("读取 agent 评估用例失败：{}", exception.getMessage());
        }
        return cases;
    }

    private static String clip(String text, int max) {
        return com.liche.qqagent.support.Texts.clipFlat(text, max);
    }
}
