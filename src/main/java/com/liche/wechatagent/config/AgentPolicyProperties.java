package com.liche.wechatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** 通用 Agent 行为策略；业务类只消费配置，不绑定某个用户或场景。 */
@ConfigurationProperties(prefix = "agent.policy")
public class AgentPolicyProperties {

    public static final String DEFAULT_PERSONA =
            "你是用户的专属长期智能助手，说话自然口语化，像真人一样沟通，拒绝生硬机械的机器人话术。"
                    + "你会自动记住用户的重要信息和目标，帮用户设置提醒、搜索资料。"
                    + "用户可以随时用 /set-prompt 指令重新设定你的身份和性格。";
    public static final String DEFAULT_CURRENT_TIME_PATTERN =
            "(?s).*?(?:现在(?:是)?几点|当前(?:是)?几点|(?:现在|当前)(?:的)?时间|今天(?:是)?(?:几号|星期几|周几|日期)).*";
    private static final Map<String, String> DEFAULT_TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("searchWeb", "搜索"),
            Map.entry("searchLatestWeb", "搜索"),
            Map.entry("readWebPage", "读取网页"),
            Map.entry("getCurrentTime", "获取时间"),
            Map.entry("parseReminder", "创建提醒"),
            Map.entry("replaceReminder", "调整提醒"),
            Map.entry("cancelReminder", "取消提醒"),
            Map.entry("listReminders", "查看提醒"),
            Map.entry("getReminderStatus", "查询提醒状态"),
            Map.entry("saveImportantMedia", "保存文件"),
            Map.entry("inspectRecentUnstoredMedia", "查看刚才的媒体"),
            Map.entry("listStoredMedia", "查找已保存文件"),
            Map.entry("readStoredMedia", "读取已保存文件"),
            Map.entry("inspectStoredMedia", "审阅文件"),
            Map.entry("deleteStoredMedia", "删除文件"),
            Map.entry("findDownloadableLinks", "查找下载文件"),
            Map.entry("downloadWebFile", "下载文件"),
            Map.entry("sendDownloadedFile", "发送文件"));

    private String defaultPersona = DEFAULT_PERSONA;
    private String currentTimePattern = DEFAULT_CURRENT_TIME_PATTERN;
    private int maxPersonaChars = 2_000;
    private Map<String, String> toolDisplayNames = new LinkedHashMap<>(DEFAULT_TOOL_DISPLAY_NAMES);

    public String getDefaultPersona() {
        return defaultPersona;
    }

    public void setDefaultPersona(String defaultPersona) {
        if (defaultPersona != null && !defaultPersona.isBlank()) {
            this.defaultPersona = defaultPersona.trim();
        }
    }

    public String getCurrentTimePattern() {
        return currentTimePattern;
    }

    public void setCurrentTimePattern(String currentTimePattern) {
        if (currentTimePattern != null && !currentTimePattern.isBlank()) {
            this.currentTimePattern = currentTimePattern.trim();
        }
    }

    public int getMaxPersonaChars() {
        return maxPersonaChars;
    }

    public void setMaxPersonaChars(int maxPersonaChars) {
        this.maxPersonaChars = maxPersonaChars;
    }

    public Map<String, String> getToolDisplayNames() {
        return toolDisplayNames;
    }

    public void setToolDisplayNames(Map<String, String> toolDisplayNames) {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_TOOL_DISPLAY_NAMES);
        if (toolDisplayNames != null) {
            toolDisplayNames.forEach((name, label) -> {
                if (name != null && !name.isBlank() && label != null && !label.isBlank()) {
                    merged.put(name.trim(), label.trim());
                }
            });
        }
        this.toolDisplayNames = merged;
    }

    public static Map<String, String> defaultToolDisplayNames() {
        return DEFAULT_TOOL_DISPLAY_NAMES;
    }
}
