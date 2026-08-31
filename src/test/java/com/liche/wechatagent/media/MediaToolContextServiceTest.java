package com.liche.wechatagent.media;

import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.document.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaToolContextServiceTest {

    @Test
    void exposesOnlyCurrentMessagesNumberedCandidates() {
        MediaToolContextService context = new MediaToolContextService();
        context.bind("u1", "m1", "这是我的课表", List.of("data:image/png;base64,AA=="),
                List.of(new InboundAttachment("课表.pdf", "application/pdf", "https://example.com/a.pdf")),
                List.of(new ExtractedDocument("课表.pdf", "周一 Python", List.of(), false)));

        assertEquals("u1", context.currentUserId());
        assertEquals("image-1.png", context.requireCandidate(1).originalName());
        assertEquals("image-1.png", context.requireCandidate(0).originalName());
        assertEquals("周一 Python", context.requireCandidate(2).extractedText());
        assertTrue(context.promptSection().contains("表情包"));
        assertTrue(context.completionNotice().contains("只用于当前对话"));
        context.recordSaved(1, "第1周课表.jpg", false, false);
        assertTrue(context.hasSavedCandidate(1));
        org.junit.jupiter.api.Assertions.assertFalse(context.hasSavedCandidate(2));

        context.unbind();
        assertThrows(IllegalStateException.class, () -> context.requireCandidate(1));
    }

    @Test
    void queuesStoredImageForTheNextMultimodalModelRound() {
        MediaToolContextService context = new MediaToolContextService();
        context.bind("u1", "m1", "看看课表", List.of(), List.of(), List.of());

        context.addReadableMedia("第 13 周课表", "data:image/png;base64,AA==");

        assertEquals(1, context.consumeReadableMedia().size());
        assertTrue(context.consumeReadableMedia().isEmpty());
    }

    @Test
    void exposesRecentUnsavedUploadOnlyAfterTheAgentExplicitlyRequestsIt() {
        MediaToolContextService context = new MediaToolContextService();
        context.bind("u1", "upload", "", List.of(),
                List.of(new InboundAttachment("教务通知.pdf", "application/pdf", "https://example.com/notice.pdf")),
                List.of(new ExtractedDocument("教务通知.pdf", "选修课补选通知", List.of(), false)));
        context.unbind();

        context.bind("u1", "ordinary-message", "今天天气怎么样", List.of(), List.of(), List.of());
        assertTrue(context.promptSection().isBlank());
        assertThrows(IllegalArgumentException.class, () -> context.requireCandidate(1));

        context.bind("u1", "follow-up", "把刚才文件保存下来", List.of(), List.of(), List.of());
        assertTrue(context.promptSection().isBlank());
        String description = context.inspectRecentUnstoredMedia("刚才文件");
        assertEquals("教务通知.pdf", context.requireCandidate(1).originalName());
        assertTrue(description.contains("不是本条消息附件"));
        context.unbind();

        context.bind("u2", "follow-up", "把刚才文件保存下来", List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> context.inspectRecentUnstoredMedia("刚才文件"));
    }

    @Test
    void keepsPendingMediaSeparatedByConversationScope() {
        MediaToolContextService context = new MediaToolContextService();
        context.bind("same-user", "bot-a", "qq", "task-a", "upload", "", List.of(),
                List.of(new InboundAttachment("课表.pdf", "application/pdf", "https://example.com/schedule.pdf")),
                List.of(new ExtractedDocument("课表.pdf", "周一 Python", List.of(), false)),
                List.of(), List.of(), List.of());
        context.unbind();

        context.bind("same-user", "bot-b", "simulator", "task-b", "follow-up", "保存刚才文件", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> context.inspectRecentUnstoredMedia("刚才文件"));
    }

    @Test
    void requiresTheAgentToProvideTheUsersActualReferenceBeforeLookingUpOldMedia() {
        MediaToolContextService context = new MediaToolContextService();
        context.bind("u1", "upload", "", List.of("data:image/png;base64,AA=="), List.of(), List.of());
        context.unbind();
        context.bind("u1", "follow-up", "天气怎么样", List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> context.inspectRecentUnstoredMedia(""));
    }

    @Test
    void makesMediaFromTheCurrentQuotedMessageAvailableToTheAgent() {
        MediaToolContextService context = new MediaToolContextService();

        context.bind("u1", "qq", "qq", "task-a", "reply", "这个文件是什么", List.of(), List.of(), List.of(),
                List.of("data:image/png;base64,AA=="),
                List.of(new InboundAttachment("课程通知.pdf", "application/pdf", "https://example.com/notice.pdf")),
                List.of(new ExtractedDocument("课程通知.pdf", "下周调整课程", List.of(), false)));

        assertTrue(context.requireCandidate(1).image());
        assertEquals("课程通知.pdf", context.requireCandidate(2).originalName());
        assertEquals("下周调整课程", context.requireCandidate(2).extractedText());
    }

}
