package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * QQ 内联表情标签清洗（2026-10-27 修的线上问题）。
 *
 * <p>背景：入站文本里表情是 `<faceType=1,faceId="5",ext="...">` 这种标签，
 * 原样进上下文时模型只看到一串乱码——实测它回了用户一句"这串乱码你自己刷得挺开心啊"。
 */
class QqInlineTagsTest {

    @Test
    void convertsFaceTagToReadableMarker() {
        assertEquals("[表情]",
                QqChannel.cleanInlineTags("<faceType=1,faceId=\"5\",ext=\"eyJ0ZXh0Ijoi5rWB5rOqIn0=\">"));
    }

    @Test
    void collapsesRepeatedMarkers() {
        // 用户连刷表情不能变成一长串 [表情][表情][表情]
        assertEquals("[表情]", QqChannel.cleanInlineTags(
                "<faceType=1,faceId=\"5\"><faceType=1,faceId=\"5\"><faceType=1,faceId=\"5\">"));
    }

    @Test
    void keepsSurroundingText() {
        assertEquals("好啊 [表情] 走起",
                QqChannel.cleanInlineTags("好啊 <faceType=1,faceId=\"5\"> 走起"));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertEquals("今天下午三点上课", QqChannel.cleanInlineTags("今天下午三点上课"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", QqChannel.cleanInlineTags(null));
        assertEquals("", QqChannel.cleanInlineTags("   "));
    }
}
