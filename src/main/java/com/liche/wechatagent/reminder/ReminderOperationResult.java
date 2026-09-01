package com.liche.wechatagent.reminder;

/** 提醒业务层对“是否真正完成”的显式结果。 */
public record ReminderOperationResult(boolean completed, String message) {

    public ReminderOperationResult {
        message = message == null ? "" : message.trim();
    }

    public static ReminderOperationResult completed(String message) {
        return new ReminderOperationResult(true, message);
    }

    public static ReminderOperationResult notCompleted(String message) {
        return new ReminderOperationResult(false, message);
    }
}
