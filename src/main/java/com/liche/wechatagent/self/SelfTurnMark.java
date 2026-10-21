package com.liche.wechatagent.self;

/**
 * 「这一轮是不是她自己在跑」（2026-10-21）。
 *
 * <p>为什么要这个标记：她要能自己决定画图，但**自主画**和**被要求画**的额度不一样——
 * 用户的原话是「一天最多 5 张，这是它自己自主模块的额度；但是如果是我让她画的则不算」。
 * 工具层没法从参数里看出"这算谁的"，只能靠调用链上的上下文。
 *
 * <p>做法和 {@code TurnProgress} 一样：{@link SelfTurnRunner} 开跑时装上、跑完在 finally 里清掉。
 * 线程复用是必须处理的——漏了 clear 会让**下一个用户对话**也被当成自主轮（于是画图被限额）。
 */
public final class SelfTurnMark {

    private static final ThreadLocal<Boolean> CURRENT = new ThreadLocal<>();

    private SelfTurnMark() {
    }

    /** 自主轮开始 */
    public static void install() {
        CURRENT.set(Boolean.TRUE);
    }

    /** 自主轮结束（必须放 finally） */
    public static void clear() {
        CURRENT.remove();
    }

    /** 现在是不是她自己在跑 */
    public static boolean active() {
        return Boolean.TRUE.equals(CURRENT.get());
    }
}
