package com.liche.wechatagent.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 提醒自然语言解析（LLM）：把描述解析为结构化任务数据：
 * 提醒内容 / 准时触发时间 / 提前预热时间 / 重复规则（Cron 表达式）。
 * 入库、校验、调度由 ReminderService（业务层）执行。
 */
@Service
public class ReminderParseService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String FENCE = "\u0060\u0060\u0060";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReminderParseService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public record ParsedReminder(String content, LocalDateTime triggerAt, Integer prewarmMinutes,
                                 String repeatCron, List<String> missing) {
    }

    public ParsedReminder parse(String description) {
        String now = LocalDateTime.now().format(FMT);
        String prompt = "你是提醒解析器。当前时间：" + now + "。\n"
                + "把用户的提醒描述解析为 JSON，格式：\n"
                + "{\"content\":\"提醒内容\",\"triggerAt\":\"yyyy-MM-dd HH:mm 或 null\","
                + "\"prewarmMinutes\":10,\"repeatCron\":\"Cron表达式或null\",\"missing\":[\"缺失信息\"]}\n"
                + "规则：\n"
                + "- 时间在过去 → triggerAt 为 null，missing 里说明'时间已过'；\n"
                + "- 时间模糊或缺失 → triggerAt 为 null，missing 里说明缺什么（如'具体几点？'）；\n"
                + "- 有重复需求（每天/每周一/每三天等）→ repeatCron 填 Quartz 标准 6 段 Cron 表达式（秒 分 时 日 月 周），否则 null；\n"
                + "- Cron 示例：每天9点 = \"0 0 9 * * ?\"；每周一早上8点 = \"0 0 8 ? * MON\"；每3天 = \"0 0 9 */3 * ?\"；必须是 6 段（秒位补 0，周位用 ? 或 MON/TUE），不要输出 5 段；\n"
                + "- prewarmMinutes 是同一条提醒提前多少分钟推送的参数，默认 10，用户提到提前时长则按其填写；\n"
                + "- 重要：用户说'提前X分钟预热/提前提醒'只是这条提醒的预热参数，绝对不要创建第二条提醒任务，\n"
                + "- content 用中文完整表述提醒内容。\n"
                + "只输出 JSON。用户描述：" + description;

        String response = chatModel.chat(prompt);
        JsonNode root = parseJson(response);

        String content = root.path("content").asText(null);
        LocalDateTime triggerAt = null;
        String triggerText = root.path("triggerAt").asText("");
        if (triggerText != null && !triggerText.isBlank() && !"null".equalsIgnoreCase(triggerText)) {
            try {
                triggerAt = LocalDateTime.parse(triggerText, FMT);
            } catch (Exception ignored) {
                triggerAt = null;
            }
        }
        Integer prewarm = root.path("prewarmMinutes").isMissingNode() ? null : root.path("prewarmMinutes").asInt();
        String cron = root.path("repeatCron").asText("");
        if (cron == null || cron.isBlank() || "null".equalsIgnoreCase(cron)) {
            cron = null;
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode m : root.path("missing")) {
            missing.add(m.asText(""));
        }
        return new ParsedReminder(content, triggerAt, prewarm, cron, missing);
    }

    private JsonNode parseJson(String text) {
        String cleaned = text == null ? "{}" : text.trim();
        if (cleaned.startsWith(FENCE)) {
            cleaned = cleaned.replaceAll(FENCE + "(json)?", "").trim();
            int end = cleaned.lastIndexOf(FENCE);
            if (end >= 0) {
                cleaned = cleaned.substring(0, end).trim();
            }
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
