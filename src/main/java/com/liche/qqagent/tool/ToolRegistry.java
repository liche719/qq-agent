package com.liche.qqagent.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.MDC;
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
    /** 注册到的工具类名，用来在启动日志里核对"有没有哪个工具忘了 implements AgentToolProvider" */
    private final java.util.Set<String> toolClasses = new java.util.LinkedHashSet<>();
    private final int retryAttempts;
    private final ToolInvocationService invocationService;
    private final com.liche.qqagent.agent.AgentTaskStateStore taskStateStore;

    @Autowired
    public ToolRegistry(List<AgentToolProvider> tools,
                         ToolInvocationService invocationService,
                         com.liche.qqagent.agent.AgentTaskStateStore taskStateStore,
                         @Value("${agent.tool-retry-attempts:1}") int retryAttempts,
                         @Value("${agent.tool-max-result-chars:8000}") int maxToolResultChars) {
        this(tools, retryAttempts, maxToolResultChars, invocationService, taskStateStore);
    }

    ToolRegistry(List<?> tools, int retryAttempts) {
        this(tools, retryAttempts, DEFAULT_MAX_TOOL_RESULT_CHARS, new ToolInvocationService(retryAttempts, DEFAULT_MAX_TOOL_RESULT_CHARS), null);
    }

    ToolRegistry(List<?> tools, int retryAttempts, int maxToolResultChars) {
        this(tools, retryAttempts, maxToolResultChars, new ToolInvocationService(retryAttempts, maxToolResultChars), null);
    }

    private ToolRegistry(List<?> tools, int retryAttempts, int maxToolResultChars,
                         ToolInvocationService invocationService,
                         com.liche.qqagent.agent.AgentTaskStateStore taskStateStore) {
        this.retryAttempts = Math.max(0, Math.min(3, retryAttempts));
        this.invocationService = invocationService;
        this.taskStateStore = taskStateStore;
        if (tools != null) {
            tools.forEach(this::register);
        }
        // 自动收集之后，"忘了 implements AgentToolProvider" 的工具会静默不注册，
        // 所以把结果打印出来：加完工具核对一眼类名和数字。
        log.info("工具注册完成：{} 个类 / {} 个工具 -> {}",
                toolClasses.size(), entries.size(), String.join("、", toolClasses));
    }

    /** Number of automatic retries configured for repeatable tools. */
    public int retryAttempts() {
        return retryAttempts;
    }

    private void register(Object tool) {
        toolClasses.add(tool.getClass().getSimpleName());
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
                // 声明了"需要确认"却没说确认参数是哪个，validateConfirmation 会直接 return——
                // 等于挂了一道永不生效的门。发现这种组合要吵出来，别让它再悄悄出现。
                if (snapshot.requiresConfirmation() && snapshot.confirmationParameter().isBlank()) {
                    log.warn("工具 {} 声明 requiresConfirmation 但没给 confirmationParameter，确认校验不会生效（要么补参数，要么去掉声明）", name);
                }
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

    /**
     * 工具元信息（2026-10-22）：给 {@code discoverTools} 做"按需解锁"用。
     *
     * @param providerClass 提供方类名——{@link ToolCatalog} 靠它归类，不靠工具名硬编码
     */
    public record ToolMeta(String name, String description, String providerClass) {
    }

    public List<ToolMeta> allMetadata() {
        List<ToolMeta> list = new ArrayList<>(entries.size());
        for (ToolEntry entry : entries.values()) {
            list.add(new ToolMeta(entry.name(), entry.spec().description(),
                    entry.tool().getClass().getSimpleName()));
        }
        return list;
    }

    /**
     * 只保留作用域允许的工具（三期领域②："它自己的时间"）。
     *
     * @param scope 作用域；null 或未限定 = 全量，行为不变
     */
    public List<ToolSpecification> specificationsOf(com.liche.qqagent.agent.TurnScope scope) {
        if (scope == null || !scope.isScoped()) {
            return specifications();
        }
        List<ToolSpecification> list = new ArrayList<>();
        for (ToolEntry e : entries.values()) {
            if (scope.allowsProvider(providerName(e.tool())) && !scope.denies(e.name())) {
                list.add(e.spec());
            }
        }
        return list;
    }

    /**
     * 这个工具名在当前作用域里允不允许。
     *
     * <p>为什么只过滤 schema 不够：模型有可能**幻觉出**一个被藏起来的工具名，而 {@link #execute}
     * 是按名字查表的——不在这里再挡一道，藏起来的工具照样能被调起来。安全边界不能靠"它应该没见过"。
     */
    public boolean isProvidedBy(String name, com.liche.qqagent.agent.TurnScope scope) {
        if (scope == null || !scope.isScoped()) {
            return true;
        }
        ToolEntry entry = entries.get(name);
        return entry != null && scope.allowsProvider(providerName(entry.tool())) && !scope.denies(name);
    }

    /** CGLIB 代理下 getClass() 会是子类，所以取 user class 再取简单名 */
    private static String providerName(Object tool) {
        return org.springframework.util.ClassUtils.getUserClass(tool.getClass()).getSimpleName();
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
        // 2026-10-28：执行前按 schema 校验参数。缺必填/类型明显不符时**不执行**，
        // 直接把可读的错误回传给模型（"缺少必填参数：query（可用参数：query、count）"），
        // 而不是让它在工具内部炸成 NullPointerException 再瞎猜。
        String invalid = ToolArgumentValidator.validate(entry.spec(), request.arguments());
        if (invalid != null) {
            log.info("工具参数校验未通过 name={} reason={}", request.name(), invalid);
            return ToolExecutionOutcome.failure(invalid, 0);
        }
        ToolPolicySnapshot policy = ToolPolicySnapshot.from(entry.policy());
        if (taskStateStore != null && (policy.hasSideEffect() || policy.destructive()
                || entry.method().isAnnotationPresent(NonIdempotentTool.class))) {
            taskStateStore.markUnsafeToReplay(MDC.get("taskId"), "已开始执行有副作用工具：" + request.name());
        }
        return invocationService.invoke(entry.method(), entry.tool(), request, memoryId, entry.policy());
    }
}
