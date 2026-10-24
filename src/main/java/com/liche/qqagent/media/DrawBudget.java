package com.liche.qqagent.media;

/**
 * 每轮画图张数上限（2026-10-24）。
 *
 * <p>起因很具体：用户说"按照你的这个形象画一张"，笨鱼连画三张都不像，被说"没让你画图！"之后
 * **还接着画第四张**。它没有"这一轮已经画过了"的概念，所以用线程本地计数器兜住：
 * 一轮最多 1 张，想再画得等下一轮（用户下一句话）。
 *
 * <p>用 ThreadLocal：一轮对话跑在同一线程，{@code AgentLoop} 每轮开始时 {@link #reset()}。
 */
public final class DrawBudget {

    private static final ThreadLocal<int[]> USED = ThreadLocal.withInitial(() -> new int[]{0});

    private DrawBudget() {
    }

    public static void reset() {
        USED.remove();
    }

    /** 还有额度就消耗一张并返回 true */
    public static boolean tryConsume(int maxPerTurn) {
        int[] used = USED.get();
        if (used[0] >= Math.max(1, maxPerTurn)) {
            return false;
        }
        used[0]++;
        return true;
    }

    public static int used() {
        return USED.get()[0];
    }
}
