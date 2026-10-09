package com.liche.wechatagent.tool;

/**
 * 工具方法的业务完成状态。
 *
 * <p>反射调用成功只说明 Java 方法正常返回，并不代表用户请求已经完成。例如提醒缺少时间时，
 * 方法应返回未完成状态，而不是被工具框架误标记为成功。</p>
 */
public record ToolBusinessResult(boolean successful, String content, String failureReason, boolean retryable,
                                 ToolExecutionStatus status) {

    public ToolBusinessResult(boolean successful, String content, String failureReason, boolean retryable) {
        this(successful, content, failureReason, retryable,
                successful ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED);
    }

    public ToolBusinessResult {
        content = content == null ? "" : content.trim();
        status = status == null ? (successful ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED) : status;
        if (successful && status != ToolExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("成功业务结果必须使用 SUCCEEDED 状态");
        }
        if (!successful && status == ToolExecutionStatus.SUCCEEDED) {
            status = ToolExecutionStatus.FAILED;
        }
        failureReason = successful
                ? ""
                : failureReason == null || failureReason.isBlank() ? "业务操作未完成" : failureReason.trim();
    }

    public static ToolBusinessResult success(String content) {
        return new ToolBusinessResult(true, content, "", false);
    }

    public static ToolBusinessResult failure(String reason) {
        return new ToolBusinessResult(false, reason, reason, false);
    }

    public static ToolBusinessResult retryableFailure(String reason) {
        return new ToolBusinessResult(false, reason, reason, true);
    }

    /** 不是失败，是"这事得用户再补一句"（提醒缺时间/指代不明）。不重试，也不该被说成故障。 */
    public static ToolBusinessResult needsInput(String message) {
        return new ToolBusinessResult(false, message, message, false, ToolExecutionStatus.NEEDS_INPUT);
    }

    public static ToolBusinessResult partial(String message) {
        return new ToolBusinessResult(false, message, message, false, ToolExecutionStatus.PARTIALLY_SUCCEEDED);
    }

    public static ToolBusinessResult unknown(String message) {
        return new ToolBusinessResult(false, message, message, false, ToolExecutionStatus.UNKNOWN_RESULT);
    }
}
