package com.liche.wechatagent.command;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 命令解析器：处理明确的用户管理指令，不经过大模型。 */
@Component
public class CommandRegistry {

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

    public CommandRegistry(List<CommandHandler> handlerList) {
        for (CommandHandler h : handlerList) {
            handlers.put(h.name(), h);
        }
    }

    /** 若文本以 / 开头则尝试匹配指令，返回 Optional 回复；否则 empty 表示走大模型 */
    public Optional<String> tryHandle(String text, String userId) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("\s+", 2);
        String name = parts[0].substring(1).toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        CommandHandler handler = handlers.get(name);
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
