package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.liche.wechatagent.channel.InboundAttachment;

import java.util.ArrayList;
import java.util.List;

/** Converts QQ attachment JSON into channel-neutral image and file collections. */
final class QqAttachmentParser {

    private QqAttachmentParser() {
    }

    static Payload parse(JsonNode data) {
        List<String> images = new ArrayList<>();
        List<InboundAttachment> attachments = new ArrayList<>();
        for (JsonNode attachment : data.path("attachments")) {
            String url = attachment.path("url").asText("");
            if (url.isBlank()) continue;
            String contentType = attachment.path("content_type").asText("");
            if (contentType.startsWith("image/")) {
                images.add(url);
            } else {
                attachments.add(new InboundAttachment(attachment.path("filename").asText(""), contentType, url));
            }
        }
        return new Payload(List.copyOf(images), List.copyOf(attachments));
    }

    record Payload(List<String> images, List<InboundAttachment> attachments) {
        boolean empty() {
            return images.isEmpty() && attachments.isEmpty();
        }
    }
}
