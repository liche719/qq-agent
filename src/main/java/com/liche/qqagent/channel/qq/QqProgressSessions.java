package com.liche.qqagent.channel.qq;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进度条的会话状态（2026-10-27 从 {@code QqChannel} 拆出，第 5 步）。
 *
 * <p><b>只搬状态与文本规则，HTTP 仍留在 channel</b>：这样这一步是纯数据搬家，
 * 不会动到发消息的时序（那部分风险高，留到第 5b 步）。
 *
 * <p>规则（与拆之前逐字一致）：
 * <ul>
 *   <li>每个用户一条进度会话，里面累积"· 工具：对象"这样的行；</li>
 *   <li>**最多 12 行**，超了不再追加（心跳会补"还在…"，别让进度条变成流水账）；</li>
 *   <li>平台拒了这条流式消息（{@code dead}）之后，行仍然继续攒，收尾时用普通消息补发。</li>
 * </ul>
 */
final class QqProgressSessions {

    /** 一行进度对应的一次会话 */
    static final class Session {
        final int msgSeq;
        int index;
        final StringBuilder text = new StringBuilder();
        volatile String messageId;
        volatile String replyToMsgId;
        volatile String botId;
        /** 平台已经拒绝过这一轮（msg_id 失效/流式已收尾）：别再重试，静默降级 */
        volatile boolean dead;
        /** 流式被平台拒了，改用普通消息发一次就够 */
        volatile boolean fallbackUsed;
        /** 降级时已经发出去多少字符，收尾补发剩下的 */
        volatile int fallbackSentChars;

        Session(int msgSeq) {
            this.msgSeq = msgSeq;
        }
    }

    private static final int MAX_LINES = 12;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    Session get(String userId) {
        return sessions.get(userId);
    }

    /** 已有就复用，没有就用给定的 msgSeq 新建 */
    Session computeIfAbsent(String userId, java.util.function.IntSupplier msgSeq) {
        return sessions.computeIfAbsent(userId, ignored -> new Session(msgSeq.getAsInt()));
    }

    Session remove(String userId) {
        return sessions.remove(userId);
    }

    /**
     * 追加一行进度。
     *
     * @return true = 需要把这一行发出去；false = 行数已到上限（或已经死了只攒不发）
     */
    boolean appendLine(Session session, String line) {
        if (session == null || line == null || line.isBlank()) {
            return false;
        }
        synchronized (session) {
            if (session.dead) {
                appendRaw(session, line);
                return false;
            }
            if (session.text.length() > 0 && session.text.toString().split("\n").length >= MAX_LINES) {
                return false;
            }
            appendRaw(session, line);
            return true;
        }
    }


    /** 降级之后还有没有"没补发出去的行"（2026-10-27 从 channel 搬来） */
    boolean needsFlush(Session session) {
        return session != null && session.dead && session.fallbackUsed
                && session.text.length() > session.fallbackSentChars;
    }

    /** 还没补发的那段文本（首帧之后累积的） */
    String unflushedText(Session session) {
        if (session == null || session.text.length() <= session.fallbackSentChars) {
            return "";
        }
        return session.text.substring(session.fallbackSentChars).trim();
    }

    /** 记下"已经补发到这里" */
    void markFlushed(Session session) {
        if (session != null) {
            session.fallbackSentChars = session.text.length();
        }
    }

    private void appendRaw(Session session, String line) {
        if (session.text.length() > 0) {
            session.text.append('\n');
        }
        session.text.append("· ").append(line.trim());
    }
}
