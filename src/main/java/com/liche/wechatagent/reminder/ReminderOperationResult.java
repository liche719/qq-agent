package com.liche.wechatagent.reminder;

/** 提醒业务层对“是否真正完成”的显式结果。 */
public record ReminderOperationResult(boolean completed, boolean needsInput, String message) {

    public ReminderOperationResult {
        message = message == null ? "" : message.trim();
    }

    public static ReminderOperationResult completed(String message) {
        return new ReminderOperationResult(true, false, message);
    }

    public static ReminderOperationResult notCompleted(String message) {
        return new ReminderOperationResult(false, false, message);
    }

    /**
     * 没做完，但**不是故障**——只是要用户再补一句（没听清提醒什么、缺时间、ID 对不上）。
     *
     * <p>这类文案本身就是给用户看的话术。2026-10-09 之前它和真失败走同一条路，被标成
     * {@code 【工具执行失败】}，而提示词第 9 条要求"只能依据成功结果声称完成"——
     * 实测造成过"追问被说成设置失败"（5 周 3 次，用户可见）。
     */
    public static ReminderOperationResult needsInput(String message) {
        return new ReminderOperationResult(false, true, message);
    }
}
