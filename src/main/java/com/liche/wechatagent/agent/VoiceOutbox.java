package com.liche.wechatagent.agent;

/**
 * 语音"暂存箱"（2026-10-22）——语音合成完先不急着发，**等这一轮的文字都发完再发**，
 * 这样"最后给我发条语音"才是字面意义上的最后一条。
 *
 * <p>为什么要改：语音原本是工具调用时立刻发出去的，而文字总结是最后一步生成的——
 * 结果语音总在文字前面。用户明确说过"我让它最后给我发语音，顺序明显不对"。
 *
 * <p>实现是 ThreadLocal：一轮对话跑在同一个线程上，工具和发送阶段都能看到。
 */
public final class VoiceOutbox {

    private static final ThreadLocal<String> PENDING = new ThreadLocal<>();

    private VoiceOutbox() {
    }

    /** 暂存一条待发的语音地址（后到的覆盖先到的——一轮只说一条） */
    public static void put(String audioUrl) {
        if (audioUrl != null && !audioUrl.isBlank()) {
            PENDING.set(audioUrl);
        }
    }

    public static boolean has() {
        return PENDING.get() != null;
    }

    /** 取走待发语音（取走即清空） */
    public static String drain() {
        String url = PENDING.get();
        PENDING.remove();
        return url;
    }

    public static void clear() {
        PENDING.remove();
    }
}
