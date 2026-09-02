package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.liche.wechatagent.channel.InboundAttachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Converts QQ attachment JSON into channel-neutral image and file collections. */
final class QqAttachmentParser {

    private QqAttachmentParser() {
    }

    static Payload parse(JsonNode data) {
        Set<String> images = new LinkedHashSet<>();
        Set<InboundAttachment> attachments = new LinkedHashSet<>();
        collect(data.path("attachments"), images, attachments, false, 0);
        collect(data.path("msg_elements"), images, attachments, false, 0);
        return new Payload(List.copyOf(images), List.copyOf(attachments));
    }

    private static void collect(JsonNode node, Set<String> images, Set<InboundAttachment> attachments,
                                boolean quoted, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 8 || quoted) return;
        if (node.isArray()) {
            for (JsonNode child : node) collect(child, images, attachments, quoted, depth + 1);
            return;
        }
        if (!node.isObject()) return;
        if ("103".equals(first(node, "message_type", "messageType", "msg_type", "msgType"))) {
            return;
        }
        String url = first(node, "url", "resource_url", "resourceUrl", "file_url", "fileUrl");
        String contentType = first(node, "content_type", "contentType", "mime_type", "mimeType");
        if (!url.isBlank()) {
            if (contentType.startsWith("image/")) images.add(url);
            else attachments.add(new InboundAttachment(first(node, "filename", "file_name", "fileName", "name"), contentType, url));
        }
        collect(node.path("attachments"), images, attachments, false, depth + 1);
        collect(node.path("msg_elements"), images, attachments, false, depth + 1);
    }

    private static String first(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    record Payload(List<String> images, List<InboundAttachment> attachments) {
        boolean empty() {
            return images.isEmpty() && attachments.isEmpty();
        }
    }
}
