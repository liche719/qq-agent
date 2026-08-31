package com.liche.wechatagent.reminder;

import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/** 提醒推送文案：自然口语化，避免模板化冰冷话术 */
@Service
public class ReminderTextService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    public String created(ReminderTask task) {
        String time = task.getTriggerAt().format(FMT);
        String cronNote = task.getCron() != null && !task.getCron().isBlank() ? "，之后会按你的规则重复提醒" : "";
        String prewarmNote = task.getPrewarmMinutes() != null && task.getPrewarmMinutes() > 0
                ? "，提前" + task.getPrewarmMinutes() + "分钟我会再提醒你一次" : "";
        return "好，我记下了：「" + task.getContent() + "」，会在 " + time + " 提醒你" + cronNote + prewarmNote + "。";
    }

    public String onTime(ReminderTask task) {
        return "到点啦！你之前让我提醒你：「" + task.getContent() + "」";
    }

    public String prewarm(ReminderTask task) {
        return "提前提醒你一下：「" + task.getContent() + "」还有 " + task.getPrewarmMinutes() + " 分钟就到啦。";
    }
}
