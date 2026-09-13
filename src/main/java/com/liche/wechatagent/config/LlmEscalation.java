package com.liche.wechatagent.config;

/**
 * 本轮任务是否被"临时升档"（由 {@code thinkDeeper} 工具触发）。
 *
 * <p>为什么用 ThreadLocal：升档要影响**同一任务内后续所有 model round**——思考开关、max_tokens、允许的工具轮数、
 * 流式超时，而这些点分散在 {@link com.liche.wechatagent.agent.AgentLoop} 与两个自研 ChatModel 里。
 * 给它们都加参数会牵连一长串构造器（见坑 45 的教训），所以采用与 {@code toolStatusService} 一致的绑定/解绑写法。
 *
 * <p>**工作线程是复用的**：必须在任务开始时清一次（{@code AgentLoop.chat()} 入口），否则上一轮的升档会串到下一轮。
 */
public final class LlmEscalation {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private LlmEscalation() {
    }

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** 置位；额度校验由调用方（工具）负责，这里不管配额 */
    public static void escalate() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void clear() {
        ACTIVE.remove();
    }

    /** 升档后的有效档位：对话档 → 深度对话档（指标里单独一个场景，方便看效果） */
    public static LlmScenario effective(LlmScenario base) {
        return active() && base == LlmScenario.DIALOG ? LlmScenario.DIALOG_DEEP : base;
    }
}
