package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划与反思的判据（2026-10-27 从 AgentLoop 拆出）。
 * 这些判断决定"要不要多花一次调用"，判错了要么白花钱、要么该反思时不反思。
 */
class PlanCoordinatorTest {

    private static Map<String, ToolExecutionOutcome> failed(String name, int attempts) {
        Map<String, ToolExecutionOutcome> map = new LinkedHashMap<>();
        map.put(name, ToolExecutionOutcome.failure("timeout", attempts));
        return map;
    }

    @Test
    void reflectOnlyWhenStrategyLooksBroken() {
        // ① 工具内部重试过仍失败 → 该反思
        assertTrue(PlanCoordinator.shouldReflect(0, 8, Set.of(), failed("searchWeb", 2)));
        // ② 一次失败、轮次还早 → 不该反思（错误回传就够了）
        assertFalse(PlanCoordinator.shouldReflect(0, 8, Set.of(), failed("searchWeb", 1)));
        // ③ 轮次过半 + 一次都没成 → 该反思
        assertTrue(PlanCoordinator.shouldReflect(4, 8, Set.of(), failed("searchWeb", 1)));
        // ④ 轮次过半，但已经成功过 → 不该反思
        assertFalse(PlanCoordinator.shouldReflect(4, 8, Set.of("searchWeb"), failed("readWebPage", 1)));
        // ⑤ 没有任何失败 → 不反思
        assertFalse(PlanCoordinator.shouldReflect(6, 8, Set.of(), Map.of()));
    }

    @Test
    void distinctToolsCountsBothSides() {
        assertEquals(2, PlanCoordinator.distinctTools(Set.of("a"), failed("b", 1)));
        assertEquals(0, PlanCoordinator.distinctTools(Set.of(), Map.of()));
    }

    @Test
    void briefResultsIsShortAndMentionsBothOutcomes() {
        Map<String, ToolExecutionOutcome> results = new LinkedHashMap<>();
        results.put("searchWeb", ToolExecutionOutcome.success("ok", 1));
        String brief = PlanCoordinator.briefResults(failed("readWebPage", 1), results);
        assertTrue(brief.contains("searchWeb：成功"));
        assertTrue(brief.contains("readWebPage：失败"));
        assertTrue(brief.length() <= 241);
    }
}
