package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.schedule.ScheduledTaskService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /schedules（定时任务）：列出、暂停/恢复、立即执行。
 *
 * <p>与「提醒」的区别：定时任务到点会**真的执行一遍任务**再把结果发回来。
 * 创建和删除都走自然语言（模型调用工具，避免指令名歧义），这里只做查看与轻量操作。
 */
@Component
public class SchedulesHandler implements CommandHandler {

    private final ScheduledTaskService service;

    public SchedulesHandler(ScheduledTaskService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "schedules";
    }

    @Override
    public String description() {
        return "查看定时任务：/schedules；暂停/恢复/立即执行用 /schedules off|on|run <ID>；新建直接说「每天早上 8 点把天气发我」";
    }

    @Override
    public String handle(String args, String userId) {
        String raw = args == null ? "" : args.strip();
        try {
            if (raw.isEmpty() || "list".equalsIgnoreCase(raw) || raw.contains("查看") || raw.contains("列表")) {
                return service.listText(userId);
            }
            String[] parts = raw.split("\\s+", 2);
            String verb = parts[0].toLowerCase();
            if (parts.length < 2) {
                Long id = parseId(raw);
                if (id == null) {
                    return usage();
                }
                return detail(userId, id);
            }
            Long id = parseId(parts[1]);
            if (id == null) {
                return usage();
            }
            return switch (verb) {
                case "off", "pause", "暂停", "停", "停掉", "关闭" -> service.setEnabled(userId, id, false);
                case "on", "resume", "恢复", "开启" -> service.setEnabled(userId, id, true);
                case "run", "now", "执行", "立即执行", "跑" -> service.runNow(userId, id);
                case "delete", "del", "cancel", "删除", "取消" -> service.cancel(userId, id);
                default -> usage();
            };
        } catch (BizException exception) {
            return exception.getMessage();
        }
    }

    private String detail(String userId, Long id) {
        List<Map<String, Object>> tasks = service.describeAll(userId);
        for (Map<String, Object> task : tasks) {
            if (id.equals(task.get("id"))) {
                return "定时任务 #" + id + "「" + task.get("title") + "」\n"
                        + "执行内容：" + task.get("instruction") + "\n"
                        + "频率：" + task.get("schedule") + "（cron " + task.get("cron") + "）\n"
                        + "状态：" + (Boolean.TRUE.equals(task.get("enabled")) ? "启用" : "已暂停")
                        + "，下次 " + task.get("nextRunAt") + "，已执行 " + task.get("runCount") + " 次\n"
                        + (String.valueOf(task.get("lastResult")).isBlank() ? "" : "上次结果：" + task.get("lastResult"));
            }
        }
        return "没找到 #" + id + " 这个定时任务，发「查看定时任务」看看有哪些。";
    }

    private String usage() {
        return """
                定时任务：
                • /schedules 或「查看定时任务」— 列出所有定时任务
                • /schedules off <ID> / on <ID> — 暂停或恢复
                • /schedules run <ID> — 立刻执行一次
                • /schedules delete <ID> — 删除
                • 新建直接说需求，例如「每天早上 8 点把今天的天气发我」「每周一汇总上周聊过的重点」

                和「定时提醒」的区别：提醒到点只发一句话；定时任务到点会真的去把这件事做完，再把结果发给你。""";
    }

    private Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value.replace("#", "").strip());
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
