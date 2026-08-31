package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.channel.InboundMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record InboundMessageBatch(List<InboundMessage> messages, String replyToMsgId) {

    public InboundMessageBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("消息批次不能为空");
        }
        InboundMessage first = messages.getFirst();
        for (InboundMessage message : messages) {
            if (!Objects.equals(first.userId(), message.userId())
                    || !Objects.equals(first.channel(), message.channel())
                    || !Objects.equals(first.botId(), message.botId())) {
                throw new IllegalArgumentException("同一批次只能包含同一会话的消息");
            }
        }
        if (replyToMsgId == null || replyToMsgId.isBlank()) {
            replyToMsgId = selectReplyAnchor(messages).msgId();
        }
    }

    public static InboundMessageBatch single(InboundMessage message) {
        return new InboundMessageBatch(List.of(message), message.msgId());
    }

    public String userId() {
        return messages.getFirst().userId();
    }

    public String botId() {
        return messages.getFirst().botId();
    }

    public String channel() {
        return messages.getFirst().channel();
    }

    public InboundMessage replyAnchor() {
        return messages.stream()
                .filter(message -> Objects.equals(replyToMsgId, message.msgId()))
                .findFirst()
                .orElseGet(() -> selectReplyAnchor(messages));
    }

    public String content() {
        if (messages.size() == 1) {
            return normalizedContent(messages.getFirst());
        }
        StringBuilder result = new StringBuilder("【用户在短时间内连续发送的 ")
                .append(messages.size()).append(" 条消息，请合并理解】");
        for (int index = 0; index < messages.size(); index++) {
            result.append("\n[第").append(index + 1).append("条]")
                    .append(normalizedContent(messages.get(index)));
        }
        return result.toString();
    }

    /**
     * 用于写入后续对话历史的文本。历史中只保留“曾有媒体”的事实，
     * 不把过去的图片或文件伪装成下一条消息仍然携带的附件。
     */
    public String historyContent() {
        if (messages.size() == 1) {
            return normalizedHistoryContent(messages.getFirst());
        }
        StringBuilder result = new StringBuilder("【用户此前在短时间内连续发送的 ")
                .append(messages.size()).append(" 条消息】");
        for (int index = 0; index < messages.size(); index++) {
            result.append("\n[第").append(index + 1).append("条]")
                    .append(normalizedHistoryContent(messages.get(index)));
        }
        return result.toString();
    }

    public String quotedContent() {
        List<String> sections = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            String quote = messages.get(index).quotedContent();
            if (quote != null && !quote.isBlank()) {
                sections.add("[第" + (index + 1) + "条消息引用]\n" + quote);
            }
        }
        return String.join("\n\n", sections);
    }

    public List<String> images() {
        return flattenImages(false);
    }

    public List<String> quotedImages() {
        return flattenImages(true);
    }

    public List<InboundAttachment> attachments() {
        return flattenAttachments(false);
    }

    public List<InboundAttachment> quotedAttachments() {
        return flattenAttachments(true);
    }

    public List<String> messageIds() {
        return messages.stream()
                .map(InboundMessage::msgId)
                .filter(messageId -> messageId != null && !messageId.isBlank())
                .distinct()
                .toList();
    }

    public boolean hasContent() {
        return messages.stream().anyMatch(message -> message.content() != null && !message.content().isBlank());
    }

    public boolean hasInputMedia() {
        return !images().isEmpty() || !attachments().isEmpty()
                || !quotedImages().isEmpty() || !quotedAttachments().isEmpty();
    }

    public long firstReceivedAt() {
        return messages.stream().mapToLong(InboundMessage::timestamp).min().orElse(System.currentTimeMillis());
    }

    private List<String> flattenImages(boolean quoted) {
        List<String> result = new ArrayList<>();
        for (InboundMessage message : messages) {
            List<String> values = quoted ? message.quotedImages() : message.images();
            if (values != null) {
                result.addAll(values);
            }
        }
        return List.copyOf(result);
    }

    private List<InboundAttachment> flattenAttachments(boolean quoted) {
        List<InboundAttachment> result = new ArrayList<>();
        for (InboundMessage message : messages) {
            List<InboundAttachment> values = quoted ? message.quotedAttachments() : message.attachments();
            if (values != null) {
                result.addAll(values);
            }
        }
        return List.copyOf(result);
    }

    private static InboundMessage selectReplyAnchor(List<InboundMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            InboundMessage message = messages.get(index);
            if (message.content() != null && !message.content().isBlank()) {
                return message;
            }
        }
        return messages.getLast();
    }

    private static String normalizedContent(InboundMessage message) {
        if (message.content() != null && !message.content().isBlank()) {
            return message.content();
        }
        if (message.images() != null && !message.images().isEmpty()) {
            return "[图片]";
        }
        if (message.attachments() != null && !message.attachments().isEmpty()) {
            return "[文件]";
        }
        if ((message.quotedContent() != null && !message.quotedContent().isBlank())
                || (message.quotedImages() != null && !message.quotedImages().isEmpty())
                || (message.quotedAttachments() != null && !message.quotedAttachments().isEmpty())) {
            return "[引用消息]";
        }
        return "[空消息]";
    }

    private static String normalizedHistoryContent(InboundMessage message) {
        String content = message.content() == null ? "" : message.content();
        StringBuilder result = new StringBuilder(content);
        if (message.images() != null && !message.images().isEmpty()) {
            appendHistoryMarker(result, "[历史消息曾附带图片；图片原件未随当前消息提供]");
        }
        if (message.attachments() != null && !message.attachments().isEmpty()) {
            appendHistoryMarker(result, "[历史消息曾附带文件；文件原件未随当前消息提供]");
        }
        if ((message.quotedContent() != null && !message.quotedContent().isBlank())
                || (message.quotedImages() != null && !message.quotedImages().isEmpty())
                || (message.quotedAttachments() != null && !message.quotedAttachments().isEmpty())) {
            appendHistoryMarker(result, "[历史消息曾引用一条消息]");
        }
        return result.isEmpty() ? "[历史空消息]" : result.toString();
    }

    private static void appendHistoryMarker(StringBuilder result, String marker) {
        if (!result.isEmpty()) {
            result.append('\n');
        }
        result.append(marker);
    }
}
