package com.liche.wechatagent.tool;

/**
 * 工具方法的业务完成状态。
 *
 * <p>反射调用成功只说明 Java 方法正常返回，并不代表用户请求已经完成。例如提醒缺少时间时，
 * 方法应返回未完成状态，而不是被工具框架误标记为成功。</p>
 */
public record ToolBusinessResult(boolean successful, String content, String failureReason, boolean retryable) {

    public ToolBusinessResult {
        content = content == null ? "" : content.trim();
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
}
