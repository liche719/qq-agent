package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体归一（2026-10-26 P0-5）：只合并"规范化后完全一致"的写法，不能把两件不同的事并成一件。
 */
class MemoryEntityServiceTest {

    @Test
    void normalizesSeparatorsWhitespaceAndFullWidth() {
        assertEquals(MemoryEntityService.normalize("周二晚·课程"), MemoryEntityService.normalize("周二晚 课程"));
        assertEquals(MemoryEntityService.normalize("１０-２８ 周三"), MemoryEntityService.normalize("10-28 周三"));
        // 括号本身去掉、**内容保留**：宁可多一张卡，也不能把「物理课（A班）」和「物理课（B班）」并成一件
        assertEquals("数字系统综合设计周三", MemoryEntityService.normalize("数字系统综合设计（周三）"));
        assertEquals("周二晚课程", MemoryEntityService.normalize("周二晚·课程"));
    }

    @Test
    void keepsDifferentThingsApart() {
        // 不同日期/不同课程不能因为"看着像"就被合并
        assertNotEquals(MemoryEntityService.normalize("周二晚·数学课"), MemoryEntityService.normalize("10-28·周三·体育课"));
        assertNotEquals(MemoryEntityService.normalize("物理课"), MemoryEntityService.normalize("物理实验室"));
    }

    @Test
    void handlesBlankInput() {
        assertEquals("", MemoryEntityService.normalize(null));
        assertEquals("", MemoryEntityService.normalize("   "));
        assertEquals("注音", MemoryEntityService.normalize("（注音）"));
    }
}
