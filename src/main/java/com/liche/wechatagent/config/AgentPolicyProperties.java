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
    private static final Map<String, String> DEFAULT_TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("searchWeb", "搜索"),
            Map.entry("searchLatestWeb", "搜索"),
            Map.entry("readWebPage", "读取网页"),
            Map.entry("readBilibiliVideo", "读B站视频字幕"),
            Map.entry("browseBilibili", "刷B站"),
            Map.entry("commentBilibili", "在B站发评论"),
            Map.entry("parseReminder", "创建提醒"),
            Map.entry("replaceReminder", "调整提醒"),
            Map.entry("cancelReminder", "取消提醒"),
            Map.entry("listReminders", "查看提醒"),
            Map.entry("getReminderStatus", "查询提醒状态"),
            Map.entry("startInterviewPractice", "开始面试陪练"),
            Map.entry("recordInterviewRound", "记录本轮评分"),
            Map.entry("endInterviewPractice", "结束陪练并复盘"),
                Map.entry("createScheduledTask", "创建定时任务"),
            Map.entry("listScheduledTasks", "查看定时任务"),
            Map.entry("setScheduledTaskEnabled", "启停定时任务"),
            Map.entry("cancelScheduledTask", "删除定时任务"),
            Map.entry("runScheduledTaskNow", "立即执行定时任务"),
            Map.entry("saveImportantMedia", "保存文件"),
            Map.entry("inspectRecentUnstoredMedia", "查看刚才的媒体"),
            Map.entry("listStoredMedia", "查找已保存文件"),
            Map.entry("readStoredMedia", "读取已保存文件"),
            Map.entry("noteStoredMediaContent", "记录资料内容"),
            Map.entry("inspectStoredMedia", "审阅文件"),
            Map.entry("deleteStoredMedia", "删除文件"),
            Map.entry("findDownloadableLinks", "查找下载文件"),
            Map.entry("downloadWebFile", "下载文件"),
            Map.entry("sendDownloadedFile", "发送文件"),
        // 2026-10-20：补齐剩下的。原来只覆盖了 29 个，其余在回复底部会露出英文方法名
        // （用户看到"调用工具：selfRead、digBilibili"这种）。57 个工具全部要有中文名。
        Map.entry("searchVerifiedWeb", "交叉核对搜索"),
        Map.entry("digBilibili", "定向搜B站"),
        Map.entry("draftBilibiliComment", "写B站评论"),
        Map.entry("listMyCommentReplies", "看谁回了我"),
        Map.entry("noteWatchedVideo", "记视频笔记"),
        Map.entry("recallWatchedVideos", "翻看过的视频"),
        Map.entry("recallMemoryFacts", "查事实记忆"),
        Map.entry("recentExtractions", "看最近记住的"),
        Map.entry("searchConversation", "翻聊天记录"),
        // —— 它自己那一侧 ——
        Map.entry("selfRead", "看自己的记录"),
        Map.entry("selfRecall", "翻自己那边的对话"),
        Map.entry("selfAppend", "记自己的动态"),
        Map.entry("selfNote", "记一笔"),
        Map.entry("selfJudge", "记自己的判断"),
        Map.entry("selfDisagree", "记一次分歧"),
        Map.entry("selfLesson", "记教训"),
        Map.entry("selfQuest", "看自己的方向"),
        Map.entry("selfQuestChoose", "开自己的方向"),
        Map.entry("selfQuestStep", "改下一步"),
        Map.entry("selfQuestNote", "写方向笔记"),
        Map.entry("selfQuestRetract", "撤回方向笔记"),
        Map.entry("selfQuestClose", "收掉自己的方向"),
        Map.entry("selfQuestRest", "今天先到这"),
        Map.entry("selfWantToSay", "攒句话想说"),
        Map.entry("selfSeedNote", "记个念头"),
        Map.entry("selfSeedClose", "关掉念头"),
        // —— 它的工作区 ——
        Map.entry("selfWorkList", "看工作区"),
        Map.entry("selfWorkRead", "读工作区文件"),
        Map.entry("selfWorkWrite", "写工作区文件"),
        Map.entry("selfWorkDelete", "删工作区文件"),
        Map.entry("selfWorkMkdir", "建工作区目录"));

    private String defaultPersona = DEFAULT_PERSONA;
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
