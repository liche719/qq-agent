package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
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
    private static final int DEFAULT_RETRY_ATTEMPTS = 1;
    private static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 8_000;

    private record ToolEntry(String name, ToolSpecification spec, Object tool, Method method,
                             ToolExecutionPolicy policy) {
    }

    private final Map<String, ToolEntry> entries = new LinkedHashMap<>();
    private final int retryAttempts;
    private final ToolInvocationService invocationService;

    @Autowired
    public ToolRegistry(com.liche.wechatagent.search.SearchTool searchTool,
                         com.liche.wechatagent.search.WebPageTool webPageTool,
                         com.liche.wechatagent.tool.ReminderTool reminderTool,
                         com.liche.wechatagent.tool.TimeTool timeTool,
                         com.liche.wechatagent.media.MediaMemoryTool mediaMemoryTool,
                         com.liche.wechatagent.media.WebFileTool webFileTool,
                         ToolInvocationService invocationService,
                         @Value("${agent.tool-retry-attempts:1}") int retryAttempts,
                         @Value("${agent.tool-max-result-chars:8000}") int maxToolResultChars) {
        this(List.of(searchTool, webPageTool, reminderTool, timeTool, mediaMemoryTool, webFileTool),
                retryAttempts, maxToolResultChars, invocationService);
    }

    ToolRegistry(List<?> tools, int retryAttempts) {
        this(tools, retryAttempts, DEFAULT_MAX_TOOL_RESULT_CHARS, new ToolInvocationService(retryAttempts, DEFAULT_MAX_TOOL_RESULT_CHARS));
    }

    ToolRegistry(List<?> tools, int retryAttempts, int maxToolResultChars) {
        this(tools, retryAttempts, maxToolResultChars, new ToolInvocationService(retryAttempts, maxToolResultChars));
    }

    private ToolRegistry(List<?> tools, int retryAttempts, int maxToolResultChars,
                         ToolInvocationService invocationService) {
        this.retryAttempts = Math.max(0, Math.min(3, retryAttempts));
        this.invocationService = invocationService;
        if (tools != null) {
            tools.forEach(this::register);
        }
    }

    /** Number of automatic retries configured for repeatable tools. */
    public int retryAttempts() {
        return retryAttempts;
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
                ToolExecutionPolicy policy = m.getAnnotation(ToolExecutionPolicy.class);
                entries.put(name, new ToolEntry(name, spec, tool, m, policy));
                ToolPolicySnapshot snapshot = ToolPolicySnapshot.from(policy);
                log.info("注册工具: {} -> {}#{} class={} sideEffect={} destructive={} confirmation={} confirmationParameter={} retryable={} risk={}",
                        name, tool.getClass().getSimpleName(), m.getName(), snapshot.executionClass(),
                        snapshot.hasSideEffect(), snapshot.destructive(), snapshot.requiresConfirmation(),
                        snapshot.confirmationParameter(), snapshot.retryable(), snapshot.riskLevel());
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

    public ToolExecutionClass executionClass(String name) {
        ToolEntry entry = entries.get(name);
        return policy(name).executionClass();
    }

    public ToolPolicySnapshot policy(String name) {
        ToolEntry entry = entries.get(name);
        return entry == null ? ToolPolicySnapshot.defaults() : ToolPolicySnapshot.from(entry.policy());
    }

    public ToolExecutionOutcome execute(ToolExecutionRequest request, Object memoryId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ToolExecutionOutcome.failure("工具请求为空", 0);
        }
        ToolEntry entry = entries.get(request.name());
        if (entry == null) {
            return ToolExecutionOutcome.failure("未知工具 " + request.name(), 0);
        }
        return invocationService.invoke(entry.method(), entry.tool(), request, memoryId, entry.policy());
    }
}
