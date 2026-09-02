package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqAttachmentParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesNonImageMediaTypesAsAttachments() throws Exception {
        var data = objectMapper.readTree("""
                {"attachments":[
                  {"url":"https://example/video.mp4","content_type":"video/mp4","filename":"video.mp4"},
                  {"url":"https://example/audio.silk","content_type":"audio/silk","filename":"voice.silk"},
                  {"url":"https://example/photo.jpg","content_type":"image/jpeg","filename":"photo.jpg"}
                ]}
                """);

        QqAttachmentParser.Payload payload = QqAttachmentParser.parse(data);

        assertEquals(1, payload.images().size());
        assertEquals(2, payload.attachments().size());
        assertTrue(payload.attachments().stream().anyMatch(item -> "video/mp4".equals(item.contentType())));
        assertTrue(payload.attachments().stream().anyMatch(item -> "audio/silk".equals(item.contentType())));
    }

    @Test
    void parsesNestedElementsButDoesNotLeakQuotedMedia() throws Exception {
        var data = objectMapper.readTree("""
                {"msg_elements":[
                  {"element_type":1,"attachments":[{"url":"https://example.test/a.pdf","content_type":"application/pdf","filename":"a.pdf"}]},
                  {"message_type":103,"attachments":[{"url":"https://example.test/old.png","content_type":"image/png"}]}
                ]}
                """);

        QqAttachmentParser.Payload payload = QqAttachmentParser.parse(data);

        assertEquals(1, payload.attachments().size());
        assertEquals("a.pdf", payload.attachments().getFirst().name());
        assertTrue(payload.images().isEmpty());
    }
}
