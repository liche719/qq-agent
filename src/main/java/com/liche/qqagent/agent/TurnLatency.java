package com.liche.qqagent.agent;

/**
 * 一轮对话的延迟埋点（2026-10-26，P1-2）。
 *
 * <p>用户真正感知的是**第一个字什么时候出来**，不是"整轮总耗时"。原来没有任何地方记这个数，
 * 所以"首跳 12.7 秒"这种说法只能靠感觉。这里记两个时刻：轮次开始、第一个流式片段到达。
 */
public final class TurnLatency {

    private static final ThreadLocal<long[]> STATE = new ThreadLocal<>();

    private TurnLatency() {
    }

    /** 一轮开始 */
    public static void begin() {
        STATE.set(new long[]{System.nanoTime(), 0L});
    }

    /** 第一个片段到达（只记第一次） */
    public static void firstToken() {
        long[] state = STATE.get();
        if (state != null && state[1] == 0L) {
            state[1] = System.nanoTime();
        }
    }

    /** 首字耗时（毫秒）；没收到任何片段返回 -1 */
    public static long firstTokenMillis() {
        long[] state = STATE.get();
        if (state == null || state[1] == 0L) {
            return -1L;
        }
        return Math.max(0L, (state[1] - state[0]) / 1_000_000L);
    }

    /** 从本轮开始到现在（毫秒） */
    public static long totalMillis() {
        long[] state = STATE.get();
        if (state == null) {
            return -1L;
        }
        return Math.max(0L, (System.nanoTime() - state[0]) / 1_000_000L);
    }

    /** 一轮结束，清掉（防线程复用串数据） */
    public static void clear() {
        STATE.remove();
    }
}
