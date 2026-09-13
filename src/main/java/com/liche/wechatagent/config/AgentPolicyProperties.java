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
            Map.entry("startInterviewPractice", "开始面试陪练"),
            Map.entry("recordInterviewRound", "记录本轮评分"),
            Map.entry("endInterviewPractice", "结束陪练并复盘"),
            Map.entry("getMaimemoStudyProgress", "查询背单词进度"),
            Map.entry("viewExamPlan", "查看考研计划"),
            Map.entry("saveExamPlan", "保存考研计划"),
            Map.entry("listExamTasks", "查看今日任务"),
            Map.entry("generateExamTasks", "生成今日任务"),
            Map.entry("addExamTask", "添加考研任务"),
            Map.entry("updateExamTask", "更新任务状态"),
            Map.entry("examCheckin", "考研打卡"),
            Map.entry("examProgress", "查看考研进度"),
            Map.entry("setExamPush", "开关考研推送"),
            Map.entry("saveExamProgress", "记录章节进度"),
            Map.entry("updateExamProgress", "推进章节进度"),
            Map.entry("viewExamProgress", "查看章节进度"),
            Map.entry("addExamMistake", "记错题"),
            Map.entry("reviewExamMistake", "记错题复习结果"),
            Map.entry("viewExamMistakes", "查看错题本"),
            Map.entry("saveExamMilestone", "设置阶段里程碑"),
            Map.entry("completeExamMilestone", "完成里程碑"),
            Map.entry("viewExamMilestones", "查看里程碑"),
            Map.entry("startExamStudy", "开始学习计时"),
            Map.entry("endExamStudy", "结束学习计时"),
            Map.entry("createScheduledTask", "创建定时任务"),
            Map.entry("listScheduledTasks", "查看定时任务"),
            Map.entry("setScheduledTaskEnabled", "启停定时任务"),
            Map.entry("cancelScheduledTask", "删除定时任务"),
            Map.entry("runScheduledTaskNow", "立即执行定时任务"),
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
