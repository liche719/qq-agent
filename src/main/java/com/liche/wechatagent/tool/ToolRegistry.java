package com.liche.wechatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private record ToolEntry(String name, ToolSpecification spec, Object tool, Method method) {
    }

    private final Map<String, ToolEntry> entries = new LinkedHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    public ToolRegistry(com.liche.wechatagent.search.SearchTool searchTool,
                         com.liche.wechatagent.search.WebPageTool webPageTool,
                         com.liche.wechatagent.tool.ReminderTool reminderTool,
                         com.liche.wechatagent.tool.TimeTool timeTool,
                         com.liche.wechatagent.media.MediaMemoryTool mediaMemoryTool,
                         com.liche.wechatagent.media.WebFileTool webFileTool) {
        register(searchTool);
        register(webPageTool);
        register(reminderTool);
        register(timeTool);
        register(mediaMemoryTool);
        register(webFileTool);
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
        ToolEntry entry = entries.get(request.name());
        if (entry == null) {
            return ToolExecutionOutcome.failure("未知工具 " + request.name());
        }
        long startedAt = System.nanoTime();
        log.info("工具开始 name={} user={}", request.name(), memoryId);
        try {
            JsonNode args = om.readTree(request.arguments() == null || request.arguments().isBlank()
                    ? "{}" : request.arguments());
            Object[] params = resolveParams(entry.method(), args);
            Object result = entry.method().invoke(entry.tool(), params);
            if (result == null) {
                return ToolExecutionOutcome.success("（工具已执行，无返回内容）");
            }
            String text = String.valueOf(result);
            log.info("工具完成 name={} user={} success=true resultChars={} durationMs={}", request.name(), memoryId,
                    text.length(), java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            String clipped = text.length() > MAX_TOOL_RESULT_CHARS
                    ? text.substring(0, MAX_TOOL_RESULT_CHARS) + "…（结果过长已截断）"
                    : text;
            return ToolExecutionOutcome.success(clipped);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() == null ? ite : ite.getCause();
            log.warn("工具执行异常 name={} user={} durationMs={}", request.name(), memoryId,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), cause);
            return ToolExecutionOutcome.failure(safeMessage(cause));
        } catch (Exception e) {
            log.warn("工具执行异常 name={} user={} durationMs={}", request.name(), memoryId,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e);
            return ToolExecutionOutcome.failure(safeMessage(e));
        }
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
