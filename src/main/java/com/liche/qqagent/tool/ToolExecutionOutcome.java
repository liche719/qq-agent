package com.liche.qqagent.tool;

public record ToolExecutionOutcome(String content, boolean successful, int attempts, String failureReason,
                                   ToolExecutionStatus status) {

    public ToolExecutionOutcome(String content, boolean successful, int attempts, String failureReason) {
        this(content, successful, attempts, failureReason,
                successful ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED);
    }

    public ToolExecutionOutcome(String content, boolean successful) {
        this(content, successful, 1, successful ? "" : "未知错误");
    }

    public ToolExecutionOutcome {
        content = content == null ? "" : content;
        attempts = Math.max(0, attempts);
        status = status == null ? (successful ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED) : status;
        if (successful && status != ToolExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("成功结果必须使用 SUCCEEDED 状态");
        }
        if (!successful && status == ToolExecutionStatus.SUCCEEDED) {
            status = ToolExecutionStatus.FAILED;
        }
        failureReason = successful
                ? ""
                : failureReason == null || failureReason.isBlank() ? "未知错误" : failureReason.trim();
    }

    public static ToolExecutionOutcome success(String content) {
        return success(content, 1);
    }

    public static ToolExecutionOutcome success(String content, int attempts) {
        return new ToolExecutionOutcome(withStatus("【工具执行成功】", "SUCCESS") + "\n"
                + (content == null ? "" : content), true, attempts, "");
    }

    public static ToolExecutionOutcome failure(String message) {
        return failure(message, 1);
    }

    public static ToolExecutionOutcome failure(String message, int attempts) {
        String reason = normalizeReason(message);
        String label = attempts > 1
                ? "【工具执行失败，已自动重试 " + (attempts - 1) + " 次】"
                : "【工具执行失败】";
        return new ToolExecutionOutcome(withStatus(label, "FAILED") + "\n原因：" + reason, false, attempts, reason);
    }

    /**
     * 需要用户补一句才能继续——**不是失败**。正文直接就是该问用户的那句话，所以不加「原因：」前缀。
     */
    public static ToolExecutionOutcome needsInput(String message, int attempts) {
        String reason = normalizeReason(message);
        return new ToolExecutionOutcome(withStatus("【需要用户补充信息】", "NEEDS_INPUT") + "\n" + reason, false,
                attempts, reason, ToolExecutionStatus.NEEDS_INPUT);
    }

    public static ToolExecutionOutcome partial(String message, int attempts) {
        String reason = normalizeReason(message);
        return new ToolExecutionOutcome(withStatus("【工具部分完成】", "PARTIAL") + "\n原因：" + reason, false,
                attempts, reason, ToolExecutionStatus.PARTIALLY_SUCCEEDED);
    }

    public static ToolExecutionOutcome unknown(String message, int attempts) {
        String reason = normalizeReason(message);
        return new ToolExecutionOutcome(withStatus("【工具结果无法确认】", "UNKNOWN") + "\n原因：" + reason, false,
                attempts, reason, ToolExecutionStatus.UNKNOWN_RESULT);
    }


    /** 给模型看的结果标题统一带上机器可读状态（2026-10-29），见类注释 */
    private static String withStatus(String label, String status) {
        if (label == null || label.isBlank()) {
            return "【工具执行 status=" + status + "】";
        }
        int end = label.indexOf('】');
        if (end < 0) {
            return "【status=" + status + " " + label + "】";
        }
        return "【status=" + status + " " + label.substring(1);
    }

    private static String normalizeReason(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) + "…" : normalized;
    }
}
