package com.liche.wechatagent.tool;

public record ToolExecutionOutcome(String content, boolean successful) {

    public static ToolExecutionOutcome success(String content) {
        return new ToolExecutionOutcome("【工具执行成功】\n" + (content == null ? "" : content), true);
    }

    public static ToolExecutionOutcome failure(String message) {
        return new ToolExecutionOutcome("【工具执行失败】\n原因：" + (message == null || message.isBlank() ? "未知错误" : message), false);
    }
}
