package com.liche.qqagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具目录（2026-10-22）——给"渐进式披露"用：日常对话只带核心工具，其余靠 {@code discoverTools} 按需解锁。
 *
 * <p>为什么做：63 个工具的 schema 每轮都随请求发出去，约 5–6k token；而第一跳的
 * {@code promptTokens} 实测 1.3 万，工具占了将近一半，直接拖慢首字延迟（实测 29 秒才吐 121 个 token）。
 *
 * <p>分类按**提供方类**（每个 AgentToolProvider 是一个能力模块），不按工具名硬编码：
 * 新增工具时只要落在已知模块里，就自动归到对应类别，不会漏。
 */
public final class ToolCatalog {

    /** 提供方类 → 用户能看懂的能力名 */
    private static final Map<String, String> CATEGORY_BY_PROVIDER = Map.ofEntries(
            Map.entry("SayTool", "说话"),
            Map.entry("SendVoiceTool", "语音"),
            Map.entry("DrawPictureTool", "画图"),
            Map.entry("SearchTool", "搜索"),
            Map.entry("WebPageTool", "网页"),
            Map.entry("WebFileTool", "文件"),
            Map.entry("BilibiliTool", "B站视频"),
            Map.entry("BiliDigTool", "B站搜索"),
            Map.entry("BiliCommentTool", "B站评论"),
            Map.entry("MediaMemoryTool", "媒体记忆"),
            Map.entry("MemoryFactTool", "记忆事实"),
            Map.entry("ConversationSearchTool", "聊天记录"),
            Map.entry("ReminderTool", "提醒"),
            Map.entry("BackgroundTaskTool", "后台任务"),
            // 以下默认不常驻，需要时再解锁
            Map.entry("InterviewTool", "面试陪练"),
            Map.entry("ScheduledTaskTool", "定时任务"),
            Map.entry("AgentQuestTool", "自主模块"),
            Map.entry("AgentSeedTool", "自主模块"),
            Map.entry("AgentSelfTool", "自主模块"),
            Map.entry("SelfWorkspaceTool", "自主模块"));

    /** 常驻类别：日常对话最常用的能力 */
    private static final List<String> CORE_CATEGORIES = List.of(
            "说话", "语音", "画图", "搜索", "网页", "文件",
            "B站视频", "B站搜索", "B站评论", "媒体记忆", "记忆事实", "聊天记录", "提醒", "后台任务");

    /** 发现工具本身永远常驻，否则"想找工具却连找的工具都没有" */
    public static final String DISCOVER_TOOL = "discoverTools";

    private ToolCatalog() {
    }

    public static String categoryOf(String providerClass) {
        if (providerClass == null) {
            return "其它";
        }
        return CATEGORY_BY_PROVIDER.getOrDefault(providerClass, "其它");
    }

    /** 这个工具是否应该常驻（按它的类别判断） */
    public static boolean isCore(String providerClass) {
        if (isDiscover(providerClass)) {
            return true;
        }
        return CORE_CATEGORIES.contains(categoryOf(providerClass));
    }

    private static boolean isDiscover(String providerClass) {
        return "DiscoverToolsTool".equals(providerClass);
    }

    /** 类别 → 常驻/按需，给发现工具展示用 */
    public static Map<String, Boolean> categoryIndex() {
        Map<String, Boolean> index = new LinkedHashMap<>();
        for (String category : CATEGORY_BY_PROVIDER.values()) {
            index.putIfAbsent(category, CORE_CATEGORIES.contains(category));
        }
        return index;
    }

    /** 关键词匹配：名称 / 描述 / 类别，任意命中（空 query 返回全部非核心） */
    public static boolean matches(String query, String name, String description, String category) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        String needle = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category.toLowerCase(Locale.ROOT);
        // 整串先试（英文名、精确短语）
        if (needle.contains(q) || desc.contains(q) || cat.contains(q)
                || q.contains(needle) || q.contains(cat)) {
            return true;
        }
        // 中文没有空格切词，模型又爱写整句（「看一看我自己工作区里的文件列表」），
        // 整串比不中就按片段试：3–4 字窗口命中任意一处就算（2026-10-22 修）
        int maxLen = Math.min(4, q.length());
        for (int len = maxLen; len >= 3; len--) {
            for (int start = 0; start + len <= q.length(); start++) {
                String piece = q.substring(start, start + len);
                if (needle.contains(piece) || desc.contains(piece) || cat.contains(piece)) {
                    return true;
                }
            }
        }
        return false;
    }
}
