package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqQuoteMessageTest {

    @Test
    void parsesReferenceNestedInGatewayEnvelope() throws Exception {
        JsonNode event = new ObjectMapper().readTree("""
                {"extra":{"messageReference":{"messageId":"nested-1","message":{"content":"被引用的内容"}}}}
                """);

        QqQuoteMessage quote = QqQuoteMessage.fromEvent(event);

        assertEquals("nested-1", quote.messageId());
        assertEquals("被引用的内容", quote.content());
    }

    @Test
    void parsesReplyElementInsideMessageElements() throws Exception {
        JsonNode event = new ObjectMapper().readTree("""
                {"msg_elements":[{"element_type":7,"reply_element":{"message_id":"reply-1",
                "message":{"content":"被引用的原消息"}}}]}
                """);

        QqQuoteMessage quote = QqQuoteMessage.fromEvent(event);

        assertEquals("reply-1", quote.messageId());
        assertEquals("被引用的原消息", quote.content());
    }

    @Test
    void parsesCurrentC2cQuoteElementsWithNestedImages() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"content":"这是什么","message_type":103,
                 "msg_elements":[{"msg_idx":"REFIDX_outer","content":"原消息文字",
                   "msg_elements":[{"content":"嵌套文字","attachments":[
                     {"content_type":"image/png","url":"https://cdn.example/quoted.png"}]}]}],
                 "message_scene":{"ext":["ref_msg_idx=REFIDX_outer","msg_idx=REFIDX_current"]}}
                """));

        assertEquals("REFIDX_outer", quote.messageId());
        assertEquals("原消息文字\n嵌套文字", quote.content());
        assertEquals(List.of("https://cdn.example/quoted.png"), quote.imageUrls());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesEmbeddedQuotedTextAndImage() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"message_reference":{"message_id":"quoted-1","message":{
                  "id":"quoted-1","content":"第13周和第14周一样",
                  "attachments":[{"content_type":"image/png","url":"https://cdn.example/schedule.png"}]}}}
                """));

        assertEquals("quoted-1", quote.messageId());
        assertEquals("第13周和第14周一样", quote.content());
        assertEquals(List.of("https://cdn.example/schedule.png"), quote.imageUrls());
    }

    @Test
    void exposesQuotedDocumentNameAsReferenceContext() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"msg_elements":[{"attachments":[
                  {"content_type":"application/pdf","filename":"2025级健身指导与管理班课表.pdf"}]}],
                 "message_scene":{"ext":["ref_msg_idx=document-1"]}}
                """));

        assertEquals("document-1", quote.messageId());
        assertTrue(quote.content().contains("2025级健身指导与管理班课表.pdf"));
    }

    @Test
    void keepsQuotedDocumentAttachmentAvailableForDocumentExtraction() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"message_reference":{"message_id":"quoted-file","message":{
                  "attachments":[{"content_type":"application/pdf","filename":"课表.pdf","url":"https://cdn.example/schedule.pdf"}]}}}
                """));

        assertEquals("quoted-file", quote.messageId());
        assertEquals(1, quote.attachments().size());
        assertEquals("课表.pdf", quote.attachments().getFirst().name());
    }

    @Test
    void keepsReferenceIdWhenGatewayDoesNotEmbedMessage() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"message_reference":{"message_id":"quoted-only-id"}}
                """));

        assertEquals("quoted-only-id", quote.messageId());
        assertTrue(quote.content().isBlank());
    }

    @Test
    void parsesLookupResponse() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromLookup(objectMapper.readTree("""
                {"data":{"id":"quoted-2","content":"原课表内容"}}
                """), "fallback");

        assertEquals("quoted-2", quote.messageId());
        assertEquals("原课表内容", quote.content());
    }

    @Test
    void doesNotTreatOrdinaryMessageElementsAsAQuote() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"content":"普通消息","message_type":0,
                 "msg_elements":[{"content":"普通消息","msg_idx":"current-1",
                   "attachments":[{"content_type":"image/png","url":"https://cdn.example/current.png"}]}]}
                """));

        assertEquals("", quote.messageId());
        assertEquals("", quote.content());
        assertTrue(quote.imageUrls().isEmpty());
        assertTrue(quote.attachments().isEmpty());
    }

    @Test
    void readsQuotedFileFromElementMarkedAsQuote() throws Exception {
        QqQuoteMessage quote = QqQuoteMessage.fromEvent(objectMapper.readTree("""
                {"msg_elements":[{"message_type":103,"msg_idx":"quoted-file-2",
                  "content":"附件说明","attachments":[
                    {"content_type":"application/pdf","filename":"资料.pdf","url":"https://cdn.example/file.pdf"}]}]}
                """));

        assertEquals("quoted-file-2", quote.messageId());
        assertTrue(quote.content().contains("资料.pdf"));
        assertEquals("https://cdn.example/file.pdf", quote.attachments().getFirst().url());
    }
}
