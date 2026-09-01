package com.liche.wechatagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

/** Performs argument conversion, reflective invocation, retry, and result normalization. */
@Component
public class ToolInvocationService {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int retryAttempts;
    private final int maxResultChars;
    private final long retryDelayMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public ToolInvocationService(@org.springframework.beans.factory.annotation.Value("${agent.tool-retry-attempts:1}") int retryAttempts,
                                 @org.springframework.beans.factory.annotation.Value("${agent.tool-max-result-chars:8000}") int maxResultChars,
                                 @org.springframework.beans.factory.annotation.Value("${agent.tool-retry-delay-ms:0}") long retryDelayMillis) {
        this.retryAttempts = Math.max(0, Math.min(3, retryAttempts));
        this.maxResultChars = Math.max(256, Math.min(100_000, maxResultChars));
        this.retryDelayMillis = Math.max(0, Math.min(30_000, retryDelayMillis));
    }

    public ToolInvocationService(int retryAttempts, int maxResultChars) {
        this(retryAttempts, maxResultChars, 0);
    }

    public ToolExecutionOutcome invoke(Method method, Object target, ToolExecutionRequest request,
                                       Object memoryId) {
        return invoke(method, target, request, memoryId, method.getAnnotation(ToolExecutionPolicy.class));
    }

    public ToolExecutionOutcome invoke(Method method, Object target, ToolExecutionRequest request,
                                       Object memoryId, ToolExecutionPolicy policy) {
        try {
            JsonNode args = objectMapper.readTree(request.arguments() == null || request.arguments().isBlank()
                    ? "{}" : request.arguments());
            if (args == null || args.isNull()) args = objectMapper.createObjectNode();
            validateConfirmation(args, policy);
            Object[] parameters = resolveParameters(method, args);
            return invokeWithRetry(method, target, parameters, request.name(), memoryId, policy);
        } catch (Exception exception) {
            log.warn("工具参数解析失败 name={} user={} reason={}", request.name(), memoryId, safeMessage(exception));
            return ToolExecutionOutcome.failure(safeMessage(exception), 0);
        }
    }

    private void validateConfirmation(JsonNode args, ToolExecutionPolicy policy) {
        if (policy == null || !policy.requiresConfirmation() || policy.confirmationParameter().isBlank()) {
            return;
        }
        JsonNode token = args.get(policy.confirmationParameter());
        if (token == null || token.isNull() || token.asText().isBlank()) {
            throw new IllegalArgumentException("高风险工具缺少有效确认参数：" + policy.confirmationParameter());
        }
    }

    private ToolExecutionOutcome invokeWithRetry(Method method, Object target, Object[] parameters,
                                                 String name, Object memoryId, ToolExecutionPolicy policy) {
        Throwable lastFailure = null;
        boolean repeatable = !method.isAnnotationPresent(NonIdempotentTool.class)
                && (policy == null || policy.retryable());
        int totalAttempts = repeatable ? retryAttempts + 1 : 1;
        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            log.info("工具开始 name={} user={} attempt={}/{}", name, memoryId, attempt, totalAttempts);
            try {
                Object result = method.invoke(target, parameters);
                if (result instanceof ToolBusinessResult business) {
                    if (business.successful()) {
                        return success(name, memoryId, business.content(), attempt, startedAt);
                    }
                    lastFailure = new IllegalStateException(business.failureReason());
                    if (!business.retryable() || attempt == totalAttempts || !repeatable) {
                        return business.status() == ToolExecutionStatus.PARTIALLY_SUCCEEDED
                                ? ToolExecutionOutcome.partial(business.failureReason(), attempt)
                                : business.status() == ToolExecutionStatus.UNKNOWN_RESULT
                                ? ToolExecutionOutcome.unknown(business.failureReason(), attempt)
                                : ToolExecutionOutcome.failure(business.failureReason(), attempt);
                    }
                } else {
                    String text = result == null ? "（工具已执行，无返回内容）" : String.valueOf(result);
                    return success(name, memoryId, text, attempt, startedAt);
                }
            } catch (InvocationTargetException exception) {
                lastFailure = exception.getCause() == null ? exception : exception.getCause();
            } catch (Exception exception) {
                lastFailure = exception;
            }
            if (attempt < totalAttempts) {
                log.warn("工具执行失败，将自动重试 name={} user={} attempt={}/{} reason={}", name, memoryId,
                        attempt, totalAttempts, safeMessage(lastFailure));
                waitBeforeRetry(attempt);
            }
        }
        return ToolExecutionOutcome.failure(safeMessage(lastFailure), totalAttempts);
    }

    private void waitBeforeRetry(int attempt) {
        if (retryDelayMillis <= 0) {
            return;
        }
        long delay = Math.min(30_000, retryDelayMillis * (1L << Math.min(attempt - 1, 10)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ToolExecutionOutcome success(String name, Object memoryId, String content, int attempts, long startedAt) {
        String text = clip(content);
        log.info("工具完成 name={} user={} success=true attempts={} resultChars={} durationMs={}", name, memoryId,
                attempts, text.length(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return ToolExecutionOutcome.success(text, attempts);
    }

    private Object[] resolveParameters(Method method, JsonNode args) {
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            JsonNode value = args.get(parameters[index].getName());
            values[index] = convert(value, parameters[index].getType());
        }
        return values;
    }

    private Object convert(JsonNode value, Class<?> type) {
        if (value == null || value.isNull()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0d;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == String.class) return value.asText();
        if (type == int.class || type == Integer.class) return value.asInt();
        if (type == long.class || type == Long.class) return value.asLong();
        if (type == double.class || type == Double.class) return value.asDouble();
        if (type == boolean.class || type == Boolean.class) return value.asBoolean();
        return objectMapper.convertValue(value, type);
    }

    private String clip(String text) {
        if (text == null || text.length() <= maxResultChars) return text == null ? "" : text;
        return text.substring(0, maxResultChars) + "…（结果过长已截断）";
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
