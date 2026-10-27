package com.liche.qqagent.agent;

import com.liche.qqagent.search.SearchTool;
import com.liche.qqagent.tool.ToolExecutionOutcome;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 回复末尾的「参考来源」（2026-10-27 从 {@code AgentLoop} 拆出，第 8 步）。
 *
 * <p>两件事都只跟文本有关：
 * <ul>
 *   <li>把搜索工具结果里的链接抽出来，最多 5 条，拼成引用块；</li>
 *   <li>裁掉**模型自己**写的「参考来源」块——否则同一条回复里会出现两份来源
 *       （实测 18:14 那条：模型 6 条 + 程序 5 条）。</li>
 * </ul>
 *
 * <p>拆出来的直接好处是可测：以前只能等模型偶尔自己列来源时才能在真机上看到问题。
 */
final class ReplySources {

    private static final String SOURCE_HEADER = "> _参考来源_";
    private static final int MAX_SOURCES = 5;

    private ReplySources() {
    }

    /** 把搜索结果里的链接拼到回复末尾；没有来源就原样返回 */
    static String append(String reply, Map<String, ToolExecutionOutcome> toolResults) {
        if (reply == null || reply.isBlank() || toolResults == null || toolResults.isEmpty()) {
            return reply;
        }
        Set<String> sources = new LinkedHashSet<>();
        for (Map.Entry<String, ToolExecutionOutcome> entry : toolResults.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().toLowerCase(Locale.ROOT).contains("search")) {
                continue;
            }
            String content = entry.getValue() == null ? null : entry.getValue().content();
            if (content == null || content.isBlank()) {
                continue;
            }
            for (String line : content.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith(SearchTool.SOURCE_MARK)) {
                    sources.add(trimmed.substring(SearchTool.SOURCE_MARK.length()).strip());
                }
                if (sources.size() >= MAX_SOURCES) {
                    break;
                }
            }
            if (sources.size() >= MAX_SOURCES) {
                break;
            }
        }
        if (sources.isEmpty()) {
            return reply;
        }
        String body = stripEmbedded(reply);
        if (body.isBlank()) {
            body = reply;
        }
        StringBuilder footer = new StringBuilder(body.stripTrailing()).append("\n\n").append(SOURCE_HEADER);
        for (String source : sources) {
            footer.append("\n> ").append(source);
        }
        return footer.toString();
    }

    /**
     * 裁掉模型自己写的「参考来源」块。
     *
     * <p>只裁**末尾那段纯引用块**：从最后一个标记开始，后面每一行都以 {@code >} 开头才认为是块，
     * 正文里的引用不受影响。
     */
    static String stripEmbedded(String reply) {
        if (reply == null) {
            return "";
        }
        int index = reply.lastIndexOf(SOURCE_HEADER);
        if (index < 0) {
            return reply;
        }
        String tail = reply.substring(index);
        for (String line : tail.split("\n")) {
            if (!line.isBlank() && !line.stripLeading().startsWith(">")) {
                return reply;
            }
        }
        String head = reply.substring(0, index).stripTrailing();
        return head.isBlank() ? reply : head;
    }
}
