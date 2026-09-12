package com.liche.wechatagent.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务的自然语言解析（LLM）：把「每天早上 8 点把天气发我」解析成
 * 标题 + 执行指令 + Quartz Cron。
 *
 * <p>与提醒解析的区别：**Cron 必填**（定时任务就是重复执行的事），
 * 并且额外产出一条"到点交给 Agent 执行"的指令文本。
 */
@Service
public class ScheduledTaskParseService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZoneId zone;

    @Autowired
    public ScheduledTaskParseService(ChatModel chatModel,
                                     @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.chatModel = chatModel;
        this.zone = parseZone(timeZoneId);
    }

    /** 解析结果；cron 为 null 表示没能确定执行频率 */
    public record ParsedTask(String title, String instruction, String cron, List<String> missing) {
    }

    public ParsedTask parse(String description) {
        LocalDateTime current = LocalDateTime.now(zone);
        JsonNode root = parseJson(chatModel.chat(buildPrompt(description, current)));
        String title = trimTo(root.path("title").asText(""), 60);
        String instruction = trimTo(root.path("instruction").asText(""), 500);
        String cron = normalizeCron(root.path("cron").asText(""));
        List<String> missing = new ArrayList<>();
        for (JsonNode node : root.path("missing")) {
            missing.add(node.asText(""));
        }
        if (!isValidCron(cron)) {
            missing.add("没听出执行频率（例如「每天早上 8 点」「每周一 9 点」）");
            cron = null;
        }
        if (instruction.isBlank()) {
            instruction = description == null ? "" : description.strip();
        }
        if (title.isBlank()) {
            title = instruction.length() > 20 ? instruction.substring(0, 20) : instruction;
        }
        return new ParsedTask(title, instruction, cron, missing);
    }

    /** Cron 合法性（Quartz 6 段；缺秒位自动补 0） */
    public static boolean isValidCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            new CronExpression(cron.trim());
            return true;
        } catch (java.text.ParseException | RuntimeException exception) {
            return false;
        }
    }

    /** 兼容 5 段 Cron（缺秒位）：Quartz 需要 6 段 */
    public static String normalizeCron(String cron) {
        String value = cron == null ? "" : cron.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.split("\\s+").length < 6 ? "0 " + value : value;
    }

    /** 下一次执行时间（面板展示用） */
    public static LocalDateTime nextRun(String cron, ZoneId zone) {
        String normalized = normalizeCron(cron);
        if (normalized == null) {
            return null;
        }
        try {
            CronExpression expression = new CronExpression(normalized);
            java.util.Date next = expression.getNextValidTimeAfter(new java.util.Date());
            return next == null ? null : LocalDateTime.ofInstant(next.toInstant(), zone);
        } catch (java.text.ParseException | RuntimeException exception) {
            return null;
        }
    }

    private String buildPrompt(String description, LocalDateTime current) {
        return "你是定时任务解析器。当前时间：" + current.format(FMT) + "，时区 Asia/Shanghai。\n"
                + "把用户的请求解析为 JSON，格式：\n"
                + "{\"title\":\"任务短名（不超过10字）\",\"instruction\":\"到点要执行的指令\",\"cron\":\"Quartz 6段 Cron\",\"missing\":[\"缺失信息\"]}\n"
                + "规则：\n"
                + "- instruction 要写成一句能直接执行的指令，保留用户要的东西，例如用户说「每天早上八点把今天的天气发我」→ instruction 写「查一下我所在城市今天的天气，用一两句话告诉我」；\n"
                + "- instruction 里不要包含时间/频率（如'每天/早上八点'），那些由 cron 表达；\n"
                + "- cron 必须是 Quartz 标准 6 段（秒 分 时 日 月 周）：每天8点 = \"0 0 8 * * ?\"；每周一9点 = \"0 0 9 ? * MON\"；每3天9点 = \"0 0 9 */3 * ?\"；每小时 = \"0 0 * * * ?\"；每周一三五 21 点 = \"0 0 21 ? * MON,WED,FRI\"；\n"
                + "- 用户没给频率或频率无法确定（例如只说'记得提醒我'）→ cron 填 null，并在 missing 里说明缺什么；\n"
                + "- 用户说'每隔X分钟'这类分钟级频率也照实转成 cron（例如每5分钟 = \"0 */5 * * * ?\"）；\n"
                + "- title 是给人看的中文短名，不要带引号。\n"
                + "只输出 JSON。用户请求：" + description;
    }

    private JsonNode parseJson(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            int firstBreak = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                cleaned = cleaned.substring(firstBreak + 1, lastFence).trim();
            }
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String trimTo(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
