package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqAttachmentParserTest {

    @Test
    void separatesImagesAndFilesAndDropsMissingUrls() throws Exception {
        var data = new ObjectMapper().readTree("""
                {"attachments":[
                  {"url":"https://x/image.png","content_type":"image/png","filename":"image.png"},
                  {"url":"https://x/file.pdf","content_type":"application/pdf","filename":"file.pdf"},
                  {"content_type":"image/jpeg","filename":"missing.jpg"}
                ]}
                """);

        QqAttachmentParser.Payload payload = QqAttachmentParser.parse(data);

        assertEquals(1, payload.images().size());
        assertEquals("https://x/image.png", payload.images().get(0));
        assertEquals(1, payload.attachments().size());
        assertEquals("file.pdf", payload.attachments().get(0).name());
    }
}
