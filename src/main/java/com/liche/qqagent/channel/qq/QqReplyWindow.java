package com.liche.qqagent.channel.qq;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被动回复窗口的缓存（2026-10-27 从 {@code QqChannel} 拆出，第 6a 步）。
 *
 * <p>QQ 的被动回复要求"回复的 msg_id 仍在窗口内（默认 1 小时）"；窗口外只能发主动消息，
 * 而主动消息有每日配额。所以 channel 里维护三张表：最近消息 id、最近到达时间、按消息记的到达时间。
 *
 * <p><b>这类"共享可变状态"必须一次替换到底</b>：如果一半调用读写新类、另一半仍读写旧表，
 * 就会出现"记得住但查不到"的双份状态 bug（2026-10-27 实测过，当场回滚）。
 * 所以本类一次提供**全部**读写与淘汰接口。
 */
final class QqReplyWindow {

    private final Map<String, Long> lastRecvAt = new ConcurrentHashMap<>();
    private final Map<String, String> lastMsgIds = new ConcurrentHashMap<>();
    private final Map<String, Long> receivedAtByMessage = new ConcurrentHashMap<>();

    private static String key(String conversationId, String messageId) {
        return conversationId + "\u0000" + messageId;
    }

    /** 记下"这个会话刚收到一条消息" */
    void remember(String conversationId, String messageId, long now) {
        if (conversationId == null || messageId == null) {
            return;
        }
        lastMsgIds.put(conversationId, messageId);
        lastRecvAt.put(conversationId, now);
        receivedAtByMessage.put(key(conversationId, messageId), now);
    }

    Long receivedAt(String conversationId, String messageId) {
        if (conversationId == null || messageId == null) {
            return null;
        }
        return receivedAtByMessage.get(key(conversationId, messageId));
    }

    String lastMessageId(String conversationId) {
        return conversationId == null ? null : lastMsgIds.get(conversationId);
    }

    Long lastReceivedAt(String conversationId) {
        return conversationId == null ? null : lastRecvAt.get(conversationId);
    }

    boolean contains(String conversationId) {
        return conversationId != null && lastMsgIds.containsKey(conversationId);
    }

    /** 按 TTL 清理过期条目；剩下没有到达时间对应的 msgId 也删掉 */
    void prune(long now, long ttlMillis) {
        lastRecvAt.entrySet().removeIf(entry -> now - entry.getValue() >= ttlMillis);
        lastMsgIds.keySet().removeIf(conversationId -> !lastRecvAt.containsKey(conversationId));
        receivedAtByMessage.entrySet().removeIf(entry -> now - entry.getValue() >= ttlMillis);
    }

    /** 会话表超限时按"最久没消息"淘汰；返回被淘汰的会话 id（调用方要顺手收尾） */
    List<String> evictConversations(int maxEntries) {
        List<String> evicted = new ArrayList<>();
        int overflow = lastRecvAt.size() - Math.max(1, maxEntries);
        if (overflow <= 0) {
            return evicted;
        }
        lastRecvAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(overflow)
                .forEach(entry -> {
                    if (lastRecvAt.remove(entry.getKey(), entry.getValue())) {
                        lastMsgIds.remove(entry.getKey());
                        evicted.add(entry.getKey());
                    }
                });
        return evicted;
    }

    /** 消息到达时间表超限时按最旧淘汰 */
    void evictMessages(int maxEntries) {
        int overflow = receivedAtByMessage.size() - Math.max(1, maxEntries);
        if (overflow <= 0) {
            return;
        }
        receivedAtByMessage.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(overflow)
                .forEach(entry -> receivedAtByMessage.remove(entry.getKey(), entry.getValue()));
    }

    void clear() {
        lastRecvAt.clear();
        lastMsgIds.clear();
        receivedAtByMessage.clear();
    }

    int conversationCount() {
        return lastMsgIds.size();
    }
}
