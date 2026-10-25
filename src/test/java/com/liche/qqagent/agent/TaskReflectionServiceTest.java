package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 反思输出解析与注入文本（2026-10-26）：这段逻辑决定"要不要重规划"，坏了不会报错，只会静默变差 */
class TaskReflectionServiceTest {

    private final TaskReflectionService service =
            new TaskReflectionService(null, null, true, 200, false, 30);

    @Test
    void parsesStructuredReplanWithSurroundingNoise() {
        String raw = """
                好的，分析如下：
                {"failure_type":"wrong_plan","root_cause":"搜索词太宽","plan_is_wrong":true,
                 "should_replan":true,"revised_plan":["换精确关键词","直接读官网"],"next_action":"换关键词再搜"}
                以上。
                """;
        TaskReflectionService.Result result = service.parse(raw);
        assertTrue(result.ok());
        assertEquals("wrong_plan", result.failureType());
        assertTrue(result.replan());
        assertEquals(List.of("换精确关键词", "直接读官网"), result.revisedPlan());
        String block = result.toPromptBlock();
        assertTrue(block.contains("重新规划"));
        assertTrue(block.contains("1. 换精确关键词"));
        assertTrue(block.contains("别再重复同样的调用"));
    }

    @Test
    void parsesFixableWithoutReplan() {
        String raw = "{\"failure_type\":\"wrong_params\",\"root_cause\":\"缺参数\",\"should_replan\":false,"
                + "\"revised_plan\":[],\"next_action\":\"补上 city 参数\"}";
        TaskReflectionService.Result result = service.parse(raw);
        assertTrue(result.ok());
        assertFalse(result.replan());
        String block = result.toPromptBlock();
        assertTrue(block.contains("失败归因"));
        assertTrue(block.contains("补上 city 参数"));
    }

    @Test
    void toleratesGarbageInsteadOfThrowing() {
        assertFalse(service.parse("模型今天不想输出 JSON").ok());
        assertFalse(service.parse("").ok());
        assertFalse(service.parse(null).ok());
        // 空数组 + 要求重规划：不能给模型一个只有标题的"重新规划"
        TaskReflectionService.Result empty = service.parse("{\"should_replan\":true,\"revised_plan\":[]}");
        assertTrue(empty.ok());
        assertTrue(empty.toPromptBlock().contains("失败归因"));
    }
}
