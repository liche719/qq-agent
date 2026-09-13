package com.liche.wechatagent.command;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 命令解析器：处理明确的用户管理指令，不经过大模型。 */
@Component
public class CommandRegistry {

    public record CommandDescriptor(String name, String description) {
    }

    /** 中文别名 → 规范指令名（QQ 自定义菜单与自然语言入口都用这里的字符串） */
    private static final Map<String, String> TEXT_ALIASES = new LinkedHashMap<>();

    static {
        TEXT_ALIASES.put("帮助", "help");
        TEXT_ALIASES.put("查看记忆", "memory");
        TEXT_ALIASES.put("查看提醒", "reminders");
        TEXT_ALIASES.put("定时任务", "schedules");
        TEXT_ALIASES.put("查看定时任务", "schedules");
        TEXT_ALIASES.put("我的定时任务", "schedules");
        TEXT_ALIASES.put("开启自动记忆", "memory");
        TEXT_ALIASES.put("关闭自动记忆", "memory");
        TEXT_ALIASES.put("删除记忆", "memory");
        TEXT_ALIASES.put("设置助手人设", "set-prompt");
        TEXT_ALIASES.put("陪练", "practice");
        TEXT_ALIASES.put("开始陪练", "practice");
        TEXT_ALIASES.put("面试陪练", "practice");
        TEXT_ALIASES.put("模拟面试", "practice");
        TEXT_ALIASES.put("结束陪练", "practice");
        TEXT_ALIASES.put("开启每日复盘", "care");
        TEXT_ALIASES.put("开启每周复盘", "care");
        TEXT_ALIASES.put("关闭主动关怀", "care");
    }

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();
    /** 静态别名 + 各处理器自己声明的别名（后者由模块自带，加模块不用改这个类） */
    private final Map<String, String> aliases = new LinkedHashMap<>(TEXT_ALIASES);

    public CommandRegistry(List<CommandHandler> handlerList) {
        for (CommandHandler h : handlerList) {
            handlers.put(h.name(), h);
            for (String alias : h.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    aliases.put(alias.trim(), h.name());
                }
            }
        }
    }

    private String aliasName(String text) {
        if (text == null) {
            return null;
        }
        String key = text.strip();
        String mapped = aliases.get(key);
        return mapped != null ? mapped : key.toLowerCase();
    }

    public List<CommandDescriptor> descriptors() {
        return handlers.values().stream().map(h -> new CommandDescriptor(h.name(), h.description())).toList();
    }

    /** 兼容斜杠指令和 QQ 原生面板填入的同名文本。 */
    public Optional<String> tryHandle(String text, String userId) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim().replaceFirst("^／", "/");
        boolean slash = trimmed.startsWith("/");
        String[] parts = trimmed.split("\\s+", 2);
        String rawName = parts[0];
        if (slash) rawName = rawName.substring(1);
        String name = rawName.toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        if (!slash) {
            name = aliasName(trimmed);
            if (handlers.get(name) == null) {
                // 「中文指令 + 参数」形式（例如「陪练 面试」）：整串不是别名时退回按首词识别，余下作为参数
                String byHead = aliasName(parts[0]);
                if (handlers.get(byHead) != null) {
                    name = byHead;
                    args = parts.length > 1 ? parts[1] : "";
                }
            }
            if (name.equals("memory") && !trimmed.equals("查看记忆")) {
                args = trimmed.contains("关闭") ? "off" : trimmed.equals("删除记忆") ? "forget" : "on";
            } else if (name.equals("care")) {
                args = trimmed.contains("每日") ? "daily" : trimmed.contains("每周") ? "weekly" : "off";
            } else if (name.equals("practice")) {
                args = switch (trimmed) {
                    case "面试陪练", "模拟面试" -> "interview";
                    case "结束陪练" -> "off";
                    // 「陪练 面试」「陪练 Java 后端 3 年」这类由首词解析带来的参数保持原样
                    default -> args;
                };
            }
        }
        CommandHandler handler = handlers.get(name);
        if (!slash && handler == null) return Optional.empty();
        if (handler == null) {
            return Optional.of("我不认识 /" + name + " 这个指令，发送 /help 可以查看所有可用指令。");
        }
        return Optional.of(handler.handle(args, userId));
    }

    public String helpText() {
        StringBuilder sb = new StringBuilder("可用指令：\n");
        for (CommandHandler h : handlers.values()) {
            sb.append("/").append(h.name()).append(" — ").append(h.description()).append("\n");
        }
        sb.append("\n其他能力：我能记住你的重要信息（分核心与中期记忆）、设置定时提醒、搜索资料。");
        return sb.toString().trim();
    }
}
