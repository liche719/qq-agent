package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionClass;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行的进度播报（2026-10-27 从 {@code AgentLoop} 拆出）。
 *
 * <p><b>为什么要单独一类</b>：AgentLoop 里混着"对话控制"和"给用户播报进度"两件事。
 * 后者完全是展示逻辑（报哪一行、慢工具怎么补心跳、哪些工具不播报），和模型调用无关，
 * 放在循环里让 1500 行的类更难读。
 *
 * <p>行为与拆之前逐字一致：
 * <ul>
 *   <li>{@link #SILENT_TOOLS} 里的工具不播报（语音、sayToUser 本身就是"说话"）；</li>
 *   <li>同一个工具在同一轮只报一次（由调用方的 {@code announced} 集合保证）；</li>
 *   <li>只有 {@code SLOW_EXTERNAL} 类工具才补"还在…"心跳，最多 3 次、每 30 秒一次。</li>
 * </ul>
 */
public final class ProgressNarrator {

    /** 这些工具不需要再报一行进度：它们本身就是"说话" */
    private static final Set<String> SILENT_TOOLS = Set.of("sendVoice", "sayToUser");

    private static final ScheduledExecutorService HEARTBEAT = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "tool-progress-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ProgressNarrator() {
    }

    public static boolean silent(String toolName) {
        return SILENT_TOOLS.contains(toolName);
    }

    /** 报一句进度：同一轮同一个工具只报一次，避免连续调用刷屏 */
    public static void announce(ToolExecutionRequest request, Set<String> announced,
                                Map<String, String> displayNames) {
        if (request == null || announced == null || silent(request.name()) || !announced.add(request.name())) {
            return;
        }
        TurnProgress.hint(label(request, displayNames));
    }

    /**
     * 慢工具补心跳：{@code SLOW_EXTERNAL} 且当前有进度通道时，每 30 秒补一句"还在…"，最多 3 次。
     *
     * @return 需要调用方在工具结束时 cancel 的句柄；不需要心跳时返回 null
     */
    public static ScheduledFuture<?> heartbeat(ToolExecutionRequest request, ToolExecutionClass executionClass,
                                               Map<String, String> displayNames) {
        if (executionClass != ToolExecutionClass.SLOW_EXTERNAL) {
            return null;
        }
        java.util.function.Consumer<String> sender = TurnProgress.progressSender();
        if (sender == null) {
            return null;
        }
        String display = displayNames.getOrDefault(request.name(), request.name());
        java.util.concurrent.atomic.AtomicInteger beats = new java.util.concurrent.atomic.AtomicInteger();
        return HEARTBEAT.scheduleAtFixedRate(() -> {
            if (beats.incrementAndGet() > 3) {
                return;
            }
            try {
                sender.accept("还在" + display + "…");
            } catch (RuntimeException ignored) {
                // 进度是尽力而为，发不出去不能影响工具
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /** 进度条上那一行：工具名 + 对象摘要 */
    public static String label(ToolExecutionRequest request, Map<String, String> displayNames) {
        String display = displayNames.getOrDefault(request.name(), request.name());
        String detail = detail(request.arguments());
        return detail == null ? display : display + "：" + detail;
    }

    /** 从工具参数里挑一个最能说明"在干什么"的字段 */
    private static String detail(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(arguments);
            for (String field : new String[]{"title", "query", "bvid", "url", "text", "content", "keyword", "name"}) {
                String value = node.path(field).asText("");
                if (value.isBlank()) {
                    continue;
                }
                String cleaned = value.replaceFirst("^https?://", "").replaceAll("\\s+", " ").trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                return cleaned.length() <= 24 ? cleaned : cleaned.substring(0, 24) + "…";
            }
        } catch (Exception ignored) {
            // 参数不是 JSON 或结构不同：只显示工具名
        }
        return null;
    }
}
