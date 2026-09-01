package com.liche.wechatagent.tool;

public record ToolExecutionOutcome(String content, boolean successful, int attempts, String failureReason) {

    public ToolExecutionOutcome(String content, boolean successful) {
        this(content, successful, 1, successful ? "" : "未知错误");
    }

    public ToolExecutionOutcome {
        content = content == null ? "" : content;
        attempts = Math.max(0, attempts);
        failureReason = successful
                ? ""
                : failureReason == null || failureReason.isBlank() ? "未知错误" : failureReason.trim();
    }

    public static ToolExecutionOutcome success(String content) {
        return success(content, 1);
    }

    public static ToolExecutionOutcome success(String content, int attempts) {
        return new ToolExecutionOutcome("【工具执行成功】\n" + (content == null ? "" : content), true, attempts, "");
    }

    public static ToolExecutionOutcome failure(String message) {
        return failure(message, 1);
    }

    public static ToolExecutionOutcome failure(String message, int attempts) {
        String reason = normalizeReason(message);
        String label = attempts > 1
                ? "【工具执行失败，已自动重试 " + (attempts - 1) + " 次】"
                : "【工具执行失败】";
        return new ToolExecutionOutcome(label + "\n原因：" + reason, false, attempts, reason);
    }

    private static String normalizeReason(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) + "…" : normalized;
    }
}
