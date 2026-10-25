package com.liche.qqagent.channel.qq;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮对话的被动回复额度（2026-10-26）。
 *
 * <p><b>为什么要有</b>：QQ 对**同一条用户消息**只允许 5 次被动回复（`msg_seq` 1–5），第 6 条直接失败。
 * 而这 5 条被好几个地方各自领走——`sayToUser`（最多 3）、进度条、语音、最终回复、
 * 进度降级补发——**没有任何一处看总数**。最坏组合下，第 6 条发不出去的那条往往正是**最终回复**：
 * 用户看到了过程，看不到结论。
 *
 * <p>规则：
 * <ul>
 *   <li>总额度 {@code total}（默认 5），其中**始终预留 1 条给最终回复**；</li>
 *   <li>最终回复永远拿得到额度（哪怕别处已经用了 4）；</li>
 *   <li>播报/语音/进度在"已用 ≥ total-1"时被拒，**拒了就不发**（记日志），而不是发出去换一个失败；</li>
 *   <li>按 {@code (userId, replyToMsgId)} 记账，10 分钟没动的自动清掉，避免长跑进程里越积越多。</li>
 * </ul>
 */
final class TurnReplyBudget {

    /** 这条回复的用途：优先级从高到低 */
    enum Kind {
        FINAL,
        VOICE,
        NARRATION,
        PROGRESS
    }

    private static final long EXPIRE_MILLIS = 10 * 60 * 1000L;

    private static final class State {
        int used;
        long touchedAt = System.currentTimeMillis();
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final int total;

    TurnReplyBudget(int total) {
        this.total = Math.max(2, Math.min(10, total));
    }

    /**
     * 申请一条回复额度。
     *
     * @return true = 可以发；false = 这条额度不该用（调用方应放弃发送并记日志）
     */
    boolean tryAcquire(String userId, String replyToMsgId, Kind kind) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        purgeIfStale();
        String key = userId + "|" + (replyToMsgId == null ? "" : replyToMsgId);
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            state.touchedAt = System.currentTimeMillis();
            if (kind == Kind.FINAL) {
                // 最终回复永远放行：它才是"用户等的那句话"
                state.used = Math.min(total, state.used + 1);
                return true;
            }
            if (state.used >= total - 1) {
                return false;
            }
            state.used++;
            return true;
        }
    }

    /** 这一轮已经用掉几条（日志/排查用） */
    int used(String userId, String replyToMsgId) {
        State state = states.get(userId + "|" + (replyToMsgId == null ? "" : replyToMsgId));
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.used;
        }
    }

    private void purgeIfStale() {
        if (states.size() < 64) {
            return;
        }
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(entry -> now - entry.getValue().touchedAt > EXPIRE_MILLIS);
    }
}
