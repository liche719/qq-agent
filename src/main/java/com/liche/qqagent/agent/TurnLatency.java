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

    /**
     * 一轮开始。数组含义：{轮次开始, 第一个文本片段, 第一次 LLM 调用的开始}。
     *
     * <p>2026-10-26 修正：多工具任务里第一段文本要等工具跑完才出现，如果从"轮次开始"算，
     * 首字会变成 211 秒这种假数（那是工具耗时）。所以首字改成
     * **第一次 LLM 调用 → 第一个 token**，工具时间单独看轮次总时长。
     */
    public static void begin() {
        STATE.set(new long[]{System.nanoTime(), 0L, 0L});
    }

    /** 第一次调模型之前调用（多轮里只有第一次生效） */
    public static void startFirstCall() {
        long[] state = STATE.get();
        if (state != null && state[2] == 0L) {
            state[2] = System.nanoTime();
        }
    }

    /** 第一个片段到达（只记第一次） */
    public static void firstToken() {
        long[] state = STATE.get();
        if (state != null && state[1] == 0L) {
            state[1] = System.nanoTime();
        }
    }

    /** 首字耗时（毫秒，= 第一次 LLM 调用到第一个 token）；没收到片段返回 -1 */
    public static long firstTokenMillis() {
        long[] state = STATE.get();
        if (state == null || state[1] == 0L) {
            return -1L;
        }
        long base = state[2] == 0L ? state[0] : state[2];
        return Math.max(0L, (state[1] - base) / 1_000_000L);
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
