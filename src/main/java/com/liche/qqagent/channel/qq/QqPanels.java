package com.liche.qqagent.channel.qq;

import java.util.List;
import java.util.Map;

/**
 * 指令面板 / 快捷菜单的载荷（2026-10-27 从 {@code QqChannel} 拆出，第 7 步的第一半）。
 *
 * <p>这些是**平台的固定结构**：面板三项、菜单三项、备注名用来做幂等（已存在就不重复创建）。
 * 抽出来的直接收益是可以测——原来改错一个字段名，只能等真机看面板是否渲染失败。
 *
 * <p>HTTP 仍留在 channel：创建前要先查"是否已存在"、失败要记日志，那是编排。
 */
final class QqPanels {

    /** 面板备注名：创建前用它判断"已经建过"（改动这个值等于允许重建一次） */
    static final String REMARK = "qq-agent-c2c-v2";

    private QqPanels() {
    }

    /** 指令面板的三项（2026-10-25 用户要求只留这三项：提醒并进定时任务，复盘/关怀整块删） */
    static List<Map<String, Object>> commandPanelItems() {
        return List.of(
                Map.of("type", "command", "name", "定时任务", "desc", "查看提醒和定时任务"),
                Map.of("type", "command", "name", "设置助手人设", "desc", "调整说话方式"),
                Map.of("type", "command", "name", "帮助", "desc", "查看使用说明"));
    }

    /** 指令面板的完整请求体 */
    static Map<String, Object> commandPanelPayload() {
        return Map.of("scope", "c2c", "target_type", "all",
                "panel", Map.of("items", commandPanelItems(), "remark", REMARK));
    }

    /** 快捷菜单的请求体（点一下等于发一句话，所以用 send_message） */
    static Map<String, Object> customMenuPayload() {
        List<Map<String, Object>> items = List.of(
                Map.of("type", "send_message", "name", "定时任务", "send_message", "查看提醒"),
                Map.of("type", "send_message", "name", "设人设", "send_message", "设置助手人设"),
                Map.of("type", "send_message", "name", "帮助", "send_message", "帮助"));
        return Map.of("menu", Map.of("items", items));
    }
}
