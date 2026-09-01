package com.liche.wechatagent.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {
    private final CommandRegistry registry = new CommandRegistry(List.of(
            new Handler("help"), new Handler("memory"), new Handler("reminders"),
            new Handler("care"), new Handler("set-prompt")));

    @Test
    void handlesFriendlyPanelCommands() {
        assertEquals("memory:on", registry.tryHandle("开启自动记忆", "u").orElseThrow());
        assertEquals("care:daily", registry.tryHandle("开启每日复盘", "u").orElseThrow());
        assertEquals("set-prompt:", registry.tryHandle("设置助手人设", "u").orElseThrow());
    }

    @Test
    void keepsSlashAndFullWidthSlashCompatibility() {
        assertEquals("memory:off", registry.tryHandle("/memory off", "u").orElseThrow());
        assertEquals("help:", registry.tryHandle("／help", "u").orElseThrow());
    }

    @Test
    void doesNotTreatUnrelatedTextAsCommand() {
        assertTrue(registry.tryHandle("你好，今天怎么样", "u").isEmpty());
    }

    private record Handler(String name) implements CommandHandler {
        @Override public String description() { return name; }
        @Override public String handle(String args, String userId) { return name + ":" + args; }
    }
}
