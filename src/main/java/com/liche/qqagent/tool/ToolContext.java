package com.liche.qqagent.tool;

/**
 * 一次工具调用需要的**上下文**（2026-11-03）。
 *
 * <p><b>为什么要它</b>：工具为了超时被放到别的线程跑，而"给谁做"这些信息原来放在 ThreadLocal 里，
 * 新线程的 ThreadLocal 是空的。之前靠在每个提交点手动搬，已经漏了两次
 * （10-30 漏 ToolStatusService、11-03 漏媒体上下文）。
 *
 * <p>现在改成：调用方把上下文**显式传进执行层**，由执行层在反射调用工具的前一刻装上、调用完清掉。
 * 这样只有一个地方负责装，"忘记搬"在结构上不可能发生。
 *
 * @param userId        当前用户
 * @param botId         机器人 id
 * @param channel       通道（qq / simulator）
 * @param replyToMsgId  这条回复要回给哪条消息
 * @param mediaSnapshot 当前这轮的媒体上下文快照（不透明对象，由 MediaToolContextService 解释）
 */
public record ToolContext(String userId, String botId, String channel, String replyToMsgId,
                          Object mediaSnapshot) {

    public boolean valid() {
        return userId != null && !userId.isBlank();
    }
}
