package com.liche.wechatagent.tool;

public enum ToolExecutionStatus {
    SUCCEEDED,
    FAILED,
    PARTIALLY_SUCCEEDED,
    UNKNOWN_RESULT,
    /**
     * 工具没坏，只是需要用户再补一句才能继续（提醒缺时间、指代不明…）。
     *
     * <p>**为什么要单开一档**（2026-10-09 实测修）：这类结果原来走 {@code FAILED}，而提示词第 9 条写着
     * 「只能依据成功结果声称完成；失败时如实说明失败阶段和原因」——于是模型把「我需要确认一下：上午1-2节
     * 的具体上课时间？」当成故障，很可能回用户「设置提醒失败了」而不是把问题问出来。实测 5 周里这类
     * 误标有 3 次（`parseReminder` 2 次、`replaceReminder` 1 次），**用户可见**。
     */
    NEEDS_INPUT
}
