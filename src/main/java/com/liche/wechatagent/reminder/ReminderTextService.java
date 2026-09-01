package com.liche.wechatagent.reminder;

import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提醒推送文案：自然口语化，避免模板化冰冷话术 */
@Service
public class ReminderTextService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final Pattern REQUEST_PREFIX = Pattern.compile(
            "^(?:.*?)(提醒我|叫我|让我|通知我|提示我)\\s*", Pattern.DOTALL);

    public String created(ReminderTask task) {
        String time = absoluteTime(task.getTriggerAt());
        String cronNote = task.getCron() != null && !task.getCron().isBlank() ? "，之后会按你的规则重复提醒" : "";
        String prewarmNote = task.getPrewarmMinutes() != null && task.getPrewarmMinutes() > 0
                ? "，提前" + task.getPrewarmMinutes() + "分钟我会再提醒你一次" : "";
        return "好，我记下了：「" + displayContent(task.getContent()) + "」，会在 " + time + " 提醒你" + cronNote + prewarmNote + "。";
    }

    public String onTime(ReminderTask task) {
        return "到点啦！你之前让我提醒你：「" + displayContent(task.getContent()) + "」";
    }

    public String prewarm(ReminderTask task) {
        return "提前提醒你一下：「" + displayContent(task.getContent()) + "」还有 " + task.getPrewarmMinutes() + " 分钟就到啦。";
    }

    public String absoluteTime(LocalDateTime time) {
        if (time == null) {
            return "时间未确定";
        }
        String weekday = switch (time.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
        String formatted = time.format(TIME_FMT);
        int separator = formatted.indexOf(' ');
        return separator < 0 ? formatted : formatted.substring(0, separator)
                + "（周" + weekday + "）" + formatted.substring(separator + 1);
    }

    /** 只移除模型偶尔重复写入的外层“提醒我/叫我”时间前缀，保留不确定的任务正文。 */
    public String displayContent(String content) {
        if (content == null || content.isBlank()) {
            return "未命名提醒";
        }
        String normalized = content.trim();
        Matcher matcher = REQUEST_PREFIX.matcher(normalized);
        if (matcher.find() && matcher.end() < normalized.length()) {
            return normalized.substring(matcher.end()).trim();
        }
        return normalized;
    }

    public String taskContent(String content) {
        return displayContent(content);
    }
}
