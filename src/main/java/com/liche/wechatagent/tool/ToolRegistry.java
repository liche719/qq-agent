package com.liche.wechatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j @Tool 统一注册与反射执行：
 * - 扫描所有 @Tool 方法生成 ToolSpecification（供 LLM 选择）
 * - 收到 ToolExecutionRequest 后按参数名反序列化并调用
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_TOOL_RESULT_CHARS = 8000;
    private static final int DEFAULT_RETRY_ATTEMPTS = 1;

    private record ToolEntry(String name, ToolSpecification spec, Object tool, Method method) {
    }

    private final Map<String, ToolEntry> entries = new LinkedHashMap<>();
    private final ObjectMapper om = new ObjectMapper();
    private final int retryAttempts;

    @Autowired
    public ToolRegistry(com.liche.wechatagent.search.SearchTool searchTool,
                         com.liche.wechatagent.search.WebPageTool webPageTool,
                         com.liche.wechatagent.tool.ReminderTool reminderTool,
                         com.liche.wechatagent.tool.TimeTool timeTool,
                         com.liche.wechatagent.media.MediaMemoryTool mediaMemoryTool,
                         com.liche.wechatagent.media.WebFileTool webFileTool) {
        this(List.of(searchTool, webPageTool, reminderTool, timeTool, mediaMemoryTool, webFileTool),
                DEFAULT_RETRY_ATTEMPTS);
    }

    ToolRegistry(List<?> tools, int retryAttempts) {
        this.retryAttempts = Math.max(0, Math.min(DEFAULT_RETRY_ATTEMPTS, retryAttempts));
        if (tools != null) {
            tools.forEach(this::register);
        }
    }

    private void register(Object tool) {
        for (Method m : tool.getClass().getMethods()) {
            Tool ann = m.getAnnotation(Tool.class);
            if (ann == null) {
                continue;
            }
            String name = (ann.name() == null || ann.name().isBlank()) ? m.getName() : ann.name();
            try {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                entries.put(name, new ToolEntry(name, spec, tool, m));
                log.info("注册工具: {} -> {}#{}", name, tool.getClass().getSimpleName(), m.getName());
            } catch (Exception e) {
                log.warn("工具注册失败: {}#{}", tool.getClass().getSimpleName(), m.getName(), e);
            }
        }
    }

    public List<ToolSpecification> specifications() {
        List<ToolSpecification> list = new ArrayList<>();
        for (ToolEntry e : entries.values()) {
            list.add(e.spec());
        }
        return list;
    }

    public ToolExecutionOutcome execute(ToolExecutionRequest request, Object memoryId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ToolExecutionOutcome.failure("工具请求为空", 0);
        }
        ToolEntry entry = entries.get(request.name());
        if (entry == null) {
            return ToolExecutionOutcome.failure("未知工具 " + request.name(), 0);
        }
        JsonNode args;
        try {
            args = om.readTree(request.arguments() == null || request.arguments().isBlank()
                    ? "{}" : request.arguments());
            if (args == null || args.isNull()) {
                args = om.createObjectNode();
            }
            Object[] params = resolveParams(entry.method(), args);
            return invokeWithRetry(entry, params, request.name(), memoryId);
        } catch (Exception e) {
            log.warn("工具参数解析失败 name={} user={} reason={}", request.name(), memoryId, safeMessage(e));
            return ToolExecutionOutcome.failure(safeMessage(e), 0);
        }
    }

    private ToolExecutionOutcome invokeWithRetry(ToolEntry entry, Object[] params, String name, Object memoryId) {
        Throwable lastFailure = null;
        int totalAttempts = retryAttempts + 1;
        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            log.info("工具开始 name={} user={} attempt={}/{}", name, memoryId, attempt, totalAttempts);
            try {
                Object result = entry.method().invoke(entry.tool(), params);
                String text = result == null ? "（工具已执行，无返回内容）" : String.valueOf(result);
                log.info("工具完成 name={} user={} success=true attempts={} resultChars={} durationMs={}", name,
                        memoryId, attempt, text.length(),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                String clipped = text.length() > MAX_TOOL_RESULT_CHARS
                        ? text.substring(0, MAX_TOOL_RESULT_CHARS) + "…（结果过长已截断）"
                        : text;
                return ToolExecutionOutcome.success(clipped, attempt);
            } catch (InvocationTargetException exception) {
                lastFailure = exception.getCause() == null ? exception : exception.getCause();
            } catch (Exception exception) {
                lastFailure = exception;
            }
            String reason = safeMessage(lastFailure);
            if (attempt < totalAttempts) {
                log.warn("工具执行失败，将自动重试 name={} user={} attempt={}/{} reason={}", name, memoryId,
                        attempt, totalAttempts, reason);
            } else {
                log.warn("工具执行失败 name={} user={} attempts={} durationMs={} reason={}", name, memoryId,
                        attempt, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        reason, lastFailure);
            }
        }
        return ToolExecutionOutcome.failure(safeMessage(lastFailure), totalAttempts);
    }

    private Object[] resolveParams(Method m, JsonNode args) throws Exception {
        Parameter[] params = m.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            JsonNode v = args.get(params[i].getName());
            values[i] = convert(v, params[i].getType());
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Object convert(JsonNode v, Class<?> type) {
        if (v == null || v.isNull()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0d;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == String.class) return v.asText();
        if (type == int.class || type == Integer.class) return v.asInt();
        if (type == long.class || type == Long.class) return v.asLong();
        if (type == double.class || type == Double.class) return v.asDouble();
        if (type == boolean.class || type == Boolean.class) return v.asBoolean();
        return om.convertValue(v, type);
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }
}
