package com.liche.wechatagent.tool;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本轮已解锁的工具（2026-10-22）。
 *
 * <p>配合 {@link ToolCatalog} 做"渐进式披露"：默认只发核心工具，模型发现缺工具时调
 * {@code discoverTools} 搜索，命中的工具名进这里；{@code AgentLoop} 每轮构建工具清单时
 * 会把这些一并带上——**同一轮内立刻可用**，不用等下一轮对话。
 *
 * <p>用 ThreadLocal：一轮对话跑在同一个线程上，轮末自然失效，不会越攒越大。
 */
public final class ToolDiscovery {

    private static final ThreadLocal<Set<String>> UNLOCKED = ThreadLocal.withInitial(LinkedHashSet::new);

    private ToolDiscovery() {
    }

    /** 每一轮开始时清空（由 AgentLoop 调用） */
    public static void reset() {
        UNLOCKED.remove();
    }

    public static void unlock(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        Set<String> set = UNLOCKED.get();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                set.add(name);
            }
        }
    }

    public static Set<String> unlocked() {
        return UNLOCKED.get();
    }

    public static void clear() {
        UNLOCKED.remove();
    }
}
