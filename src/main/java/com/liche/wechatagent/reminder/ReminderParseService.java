package com.liche.wechatagent.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提醒自然语言解析（LLM）：把描述解析为结构化任务数据：
 * 提醒内容 / 准时触发时间 / 提前预热时间 / 重复规则（Cron 表达式）。
 * 入库、校验、调度由 ReminderService（业务层）执行。
 */
@Service
public class ReminderParseService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String FENCE = "\u0060\u0060\u0060";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("(?:周|星期|礼拜)(一|二|三|四|五|六|日|天)");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZoneId zone;
    private final int defaultPrewarmMinutes;

    @org.springframework.beans.factory.annotation.Autowired
    public ReminderParseService(ChatModel chatModel,
                                @org.springframework.beans.factory.annotation.Value("${app.time-zone:Asia/Shanghai}") String timeZoneId,
                                @org.springframework.beans.factory.annotation.Value("${reminder.default-prewarm-minutes:10}") int defaultPrewarmMinutes) {
        this.chatModel = chatModel;
        this.zone = parseZone(timeZoneId);
        this.defaultPrewarmMinutes = Math.max(0, Math.min(24 * 60, defaultPrewarmMinutes));
    }

    public ReminderParseService(ChatModel chatModel) {
        this(chatModel, "Asia/Shanghai", 10);
    }

    public record ParsedReminder(String content, LocalDateTime triggerAt, Integer prewarmMinutes,
                                 String repeatCron, List<String> missing) {
    }

    public ParsedReminder parse(String description) {
        LocalDateTime current = LocalDateTime.now(zone);
        String prompt = buildPrompt(description, current);

        JsonNode root = parseJson(chatModel.chat(prompt));
        return parseResult(root, description, current);
    }

    // Builds the structured extraction prompt with the current time and parser constraints.
    private String buildPrompt(String description, LocalDateTime current) {
        String now = current.format(FMT);
        return "你是提醒解析器。当前时间：" + now + "。\n"
                + "把用户的提醒描述解析为 JSON，格式：\n"
                + "{\"content\":\"提醒内容\",\"triggerAt\":\"yyyy-MM-dd HH:mm 或 null\","
                + "\"prewarmMinutes\":" + defaultPrewarmMinutes
                + ",\"repeatCron\":\"Cron表达式或null\",\"missing\":[\"缺失信息\"]}\n"
                + "规则：\n"
                + "- 时间在过去 → triggerAt 为 null，missing 里说明'时间已过'；\n"
                + "- 时间模糊或缺失 → triggerAt 为 null，missing 里说明缺什么（如'具体几点？'）；\n"
                + "- 有重复需求（每天/每周一/每三天等）→ repeatCron 填 Quartz 标准 6 段 Cron 表达式（秒 分 时 日 月 周），否则 null；\n"
                + "- Cron 示例：每天9点 = \"0 0 9 * * ?\"；每周一早上8点 = \"0 0 8 ? * MON\"；每3天 = \"0 0 9 */3 * ?\"；必须是 6 段（秒位补 0，周位用 ? 或 MON/TUE），不要输出 5 段；\n"
                + "- prewarmMinutes 是同一条提醒提前多少分钟推送的参数，默认 " + defaultPrewarmMinutes
                + "，用户提到提前时长则按其填写；\n"
                + "- 重要：用户说'提前X分钟预热/提前提醒'只是这条提醒的预热参数，绝对不要创建第二条提醒任务，\n"
                + "- content 只写要提醒的事情本身，不要包含‘今天/明天/后天/周几/几点/上午/下午’等时间说明，也不要写‘提醒我’这类外层请求词。\n"
                + "只输出 JSON。用户描述：" + description;
    }

    // Converts the model JSON into a validated reminder value object.
    private ParsedReminder parseResult(JsonNode root, String description, LocalDateTime current) {
        String content = root.path("content").asText(null);
        LocalDateTime triggerAt = parseTriggerAt(root.path("triggerAt").asText(""));
        JsonNode prewarmNode = root.path("prewarmMinutes");
        Integer prewarm = prewarmNode.isMissingNode() || prewarmNode.isNull()
                ? null : prewarmNode.asInt();
        String cron = root.path("repeatCron").asText("");
        if (cron == null || cron.isBlank() || "null".equalsIgnoreCase(cron)) {
            cron = null;
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode m : root.path("missing")) {
            missing.add(m.asText(""));
        }
        validateDateHints(description, triggerAt, missing, current);
        return new ParsedReminder(content, triggerAt, prewarm, cron, missing);
    }

    // Parses a model-provided trigger timestamp without allowing malformed values to escape.
    private LocalDateTime parseTriggerAt(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        try {
            return LocalDateTime.parse(value, FMT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void validateDateHints(String description, LocalDateTime triggerAt, List<String> missing,
                                   LocalDateTime current) {
        if (description == null || description.isBlank() || triggerAt == null) {
            return;
        }
        LocalDate expectedDate = null;
        String compact = description.replaceAll("\\s+", "");
        if (compact.contains("大后天")) {
            expectedDate = current.toLocalDate().plusDays(3);
        } else if (compact.contains("后天")) {
            expectedDate = current.toLocalDate().plusDays(2);
        } else if (compact.contains("明天")) {
            expectedDate = current.toLocalDate().plusDays(1);
        } else if (compact.contains("今天")) {
            expectedDate = current.toLocalDate();
        }
        Matcher monthDay = MONTH_DAY_PATTERN.matcher(compact);
        if (monthDay.find()) {
            try {
                int month = Integer.parseInt(monthDay.group(1));
                int day = Integer.parseInt(monthDay.group(2));
                int year = triggerAt.getYear();
                expectedDate = LocalDate.of(year, month, day);
            } catch (RuntimeException ignored) {
                addMissing(missing, "具体日期格式无法确认");
            }
        }
        if (expectedDate != null && !triggerAt.toLocalDate().equals(expectedDate)) {
            addMissing(missing, "日期解析与用户描述不一致，请重新说明具体日期和时间");
        }

        Matcher weekday = WEEKDAY_PATTERN.matcher(compact);
        if (weekday.find()) {
            DayOfWeek expected = switch (weekday.group(1)) {
                case "一" -> DayOfWeek.MONDAY;
                case "二" -> DayOfWeek.TUESDAY;
                case "三" -> DayOfWeek.WEDNESDAY;
                case "四" -> DayOfWeek.THURSDAY;
                case "五" -> DayOfWeek.FRIDAY;
                case "六" -> DayOfWeek.SATURDAY;
                default -> DayOfWeek.SUNDAY;
            };
            if (triggerAt.getDayOfWeek() != expected) {
                addMissing(missing, "星期解析与用户描述不一致，请重新说明具体日期和时间");
            }
        }
    }

    private void addMissing(List<String> missing, String message) {
        if (!missing.contains(message)) {
            missing.add(message);
        }
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

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
