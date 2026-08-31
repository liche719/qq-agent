package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.liche.wechatagent.channel.InboundAttachment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses the quote metadata provided by QQ gateway or its message lookup endpoint. */
record QqQuoteMessage(String messageId, String content, List<String> imageUrls, List<InboundAttachment> attachments) {

    static QqQuoteMessage fromEvent(JsonNode event) {
        QqQuoteMessage elementsQuote = fromMessageElements(event);
        if (!elementsQuote.content().isBlank() || !elementsQuote.imageUrls().isEmpty() || !elementsQuote.attachments().isEmpty()) return elementsQuote;
        JsonNode reference = findReference(event, 0);
        if (reference == null) return new QqQuoteMessage("", "", List.of(), List.of());
        JsonNode embedded = firstObject(reference, "message", "referenced_message", "referencedMessage",
                "reply_message", "replyMessage", "quoted_message", "quotedMessage");
        return fromMessage(embedded == null ? reference : embedded,
                firstText(reference, "message_id", "messageId", "id"));
    }

    static QqQuoteMessage fromLookup(JsonNode response, String fallbackMessageId) {
        JsonNode message = firstObject(response, "data", "message");
        if (message == null) message = response;
        return fromMessage(message, fallbackMessageId);
    }

    private static QqQuoteMessage fromMessage(JsonNode message, String fallbackMessageId) {
        if (message == null || message.isMissingNode()) return new QqQuoteMessage(fallbackMessageId, "", List.of(), List.of());
        String id = firstText(message, "id", "message_id", "messageId");
        if (id.isBlank()) id = fallbackMessageId == null ? "" : fallbackMessageId;
        String content = message.path("content").asText("").trim();
        List<String> images = new ArrayList<>();
        List<InboundAttachment> files = new ArrayList<>();
        for (JsonNode attachment : message.path("attachments")) {
            String url = attachment.path("url").asText("");
            String type = attachment.path("content_type").asText("");
            if (!url.isBlank() && type.startsWith("image/")) images.add(url);
            else if (!url.isBlank()) files.add(new InboundAttachment(firstText(attachment, "filename", "file_name", "fileName", "name"), type, url));
        }
        return new QqQuoteMessage(id, appendAttachments(content, message.path("attachments")), List.copyOf(images), List.copyOf(files));
    }

    /**
     * QQ's current C2C protocol represents a user quote as message_type=103.
     * The quoted payload is carried directly in msg_elements rather than in
     * the older message_reference object used by channel messages.
     */
    private static QqQuoteMessage fromMessageElements(JsonNode event) {
        JsonNode elements = event.path("msg_elements");
        if (!elements.isArray() || elements.isEmpty()) return new QqQuoteMessage("", "", List.of(), List.of());

        List<String> contentParts = new ArrayList<>();
        List<String> images = new ArrayList<>();
        List<InboundAttachment> files = new ArrayList<>();
        for (JsonNode element : elements) {
            collectElementContent(element, contentParts, images, files);
        }
        return new QqQuoteMessage(referenceMessageIndex(event), String.join("\n", contentParts), List.copyOf(images), List.copyOf(files));
    }

    private static void collectElementContent(JsonNode element, List<String> contentParts, List<String> images, List<InboundAttachment> files) {
        if (element == null || element.isMissingNode() || element.isNull()) return;
        String content = element.path("content").asText("").trim();
        if (!content.isBlank()) contentParts.add(content);
        for (JsonNode attachment : element.path("attachments")) {
            String url = attachment.path("url").asText("");
            String type = attachment.path("content_type").asText("");
            if (!url.isBlank() && type.startsWith("image/")) images.add(url);
            else { appendFileReference(contentParts, attachment); if (!url.isBlank()) files.add(new InboundAttachment(firstText(attachment, "filename", "file_name", "fileName", "name"), type, url)); }
        }
        for (JsonNode nested : element.path("msg_elements")) {
            collectElementContent(nested, contentParts, images, files);
        }
    }

    private static String appendAttachments(String content, JsonNode attachments) {
        List<String> parts = new ArrayList<>();
        if (content != null && !content.isBlank()) parts.add(content);
        for (JsonNode attachment : attachments) {
            String type = attachment.path("content_type").asText("");
            if (!type.startsWith("image/")) appendFileReference(parts, attachment);
        }
        return String.join("\n", parts);
    }

    private static void appendFileReference(List<String> contentParts, JsonNode attachment) {
        String name = firstText(attachment, "filename", "file_name", "fileName", "name", "title");
        if (name.isBlank()) name = "未命名文件";
        String type = attachment.path("content_type").asText("");
        String description = "【用户引用的文件】" + name;
        if (!type.isBlank()) description += "（" + type + "）";
        if (!contentParts.contains(description)) contentParts.add(description);
    }

    private static String referenceMessageIndex(JsonNode event) {
        for (JsonNode extension : event.path("message_scene").path("ext")) {
            String value = extension.asText("");
            if (value.startsWith("ref_msg_idx=")) return value.substring("ref_msg_idx=".length());
        }
        return "";
    }

    private static JsonNode firstObject(JsonNode source, String... names) {
        for (String name : names) {
            JsonNode node = source.path(name);
            if (node.isObject()) return node;
        }
        return null;
    }

    /** QQ gateway versions put the reference metadata either at the message root or in an envelope. */
    private static JsonNode findReference(JsonNode source, int depth) {
        if (source == null || source.isMissingNode() || depth >= 5) return null;
        JsonNode direct = firstObject(source, "message_reference", "messageReference", "reference",
                "quoted_message", "quotedMessage", "reply_to", "replyTo", "reply_element", "replyElement",
                "reference_element", "referenceElement");
        if (direct != null) return direct;
        if (source.isArray()) {
            for (JsonNode child : source) {
                JsonNode found = findReference(child, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        var fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode child = entry.getValue();
            if (child.isObject() || child.isArray()) {
                JsonNode found = findReference(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String firstText(JsonNode source, String... names) {
        for (String name : names) {
            String value = source.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
