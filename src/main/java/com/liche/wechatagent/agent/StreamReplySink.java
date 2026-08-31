package com.liche.wechatagent.agent;

/**
 * 流式回复接收器：AgentLoop 在 LLM 流式生成最终回复时，把增量文本实时推送过来。
 * 工具调用轮的过程文本不会推送（只有最终文本轮触发 onPartial/onDone）。
 */
public interface StreamReplySink {

    /** 收到一段增量文本（最终文本轮的实时片段） */
    void onPartial(String partial);

    /** 最终回复完整生成完毕 */
    void onDone(String fullText);

    /** 流式生成失败（仍会尝试通过普通通道发送兜底回复） */
    default void onError(Throwable t) {
    }

    /** 流式是否已成功推送完毕（用于跳过重复的完整文本发送） */
    default boolean isDone() {
        return false;
    }
}
