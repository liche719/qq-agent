package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 面板/菜单载荷（2026-10-27 从 QqChannel 抽出）：字段名错一个，真机面板就渲染不出来。 */
class QqPanelsTest {

    @SuppressWarnings("unchecked")
    @Test
    void commandPanelHasTheThreeItemsAndRemark() {
        Map<String, Object> payload = QqPanels.commandPanelPayload();

        assertEquals("c2c", payload.get("scope"));
        assertEquals("all", payload.get("target_type"));
        Map<String, Object> panel = (Map<String, Object>) payload.get("panel");
        assertEquals(QqPanels.REMARK, panel.get("remark"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) panel.get("items");
        assertEquals(3, items.size());
        assertEquals(List.of("定时任务", "设置助手人设", "帮助"),
                items.stream().map(item -> (String) item.get("name")).toList());
        items.forEach(item -> assertEquals("command", item.get("type")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void customMenuSendsMessages() {
        Map<String, Object> menu = (Map<String, Object>) QqPanels.customMenuPayload().get("menu");
        List<Map<String, Object>> items = (List<Map<String, Object>>) menu.get("items");
        assertEquals(3, items.size());
        items.forEach(item -> assertEquals("send_message", item.get("type")));
        assertEquals("设置助手人设", items.get(1).get("send_message"));
    }
}
