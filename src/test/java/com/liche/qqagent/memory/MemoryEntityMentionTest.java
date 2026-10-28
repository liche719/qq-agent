package com.liche.qqagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体命中（2026-10-28）：查询里的**别名**要能对上事实里的规范写法。
 *
 * <p>这是"用户用别名提问就召回不到"的修复基础——`周三·课` 与 `周三课` 是同一个实体，
 * 但向量相似度会因为多出来的分隔符掉分。</p>
 */
class MemoryEntityMentionTest {

    @Test
    void blankInputReturnsEmpty() {
        // 纯函数部分：没有 repository 时不能抛
        MemoryEntityService service = new MemoryEntityService(null);
        assertTrue(service.mentioned(null, "周三课").isEmpty());
        assertTrue(service.mentioned("u1", null).isEmpty());
        assertTrue(service.mentioned("u1", "   ").isEmpty());
    }

    @Test
    void canonicalizeReadOnlyFallsBackWithoutRepository() {
        MemoryEntityService service = new MemoryEntityService(null);
        // 没有库时返回归一化结果本身，不能 NPE
        assertEquals("周三课", service.canonicalizeReadOnly("u1", " 周三 课 "));
        assertEquals("", service.canonicalizeReadOnly("u1", null));
    }
}
