package com.liche.wechatagent.media;

import com.liche.wechatagent.network.PublicUrlValidator;
import com.liche.wechatagent.tool.ToolStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFileToolTest {

    @Test
    void recognizesSchoolAttachmentLinksWithoutFileExtensionsInTheirUrl() throws Exception {
        WebFileTool tool = new WebFileTool(Mockito.mock(MediaStorageService.class),
                Mockito.mock(ToolStatusService.class), new PublicUrlValidator());
        Method collectLinks = WebFileTool.class.getDeclaredMethod("collectLinks", java.net.URI.class, String.class);
        collectLinks.setAccessible(true);

        String result = (String) collectLinks.invoke(tool, java.net.URI.create("https://jwb.dgut.edu.cn/info/1261/29391.htm"),
                "<div class='Annex'><a href='/system/_content/download.jsp?wbfileid=abc'>跟班重修表.doc</a></div>");

        assertTrue(result.contains("download.jsp?wbfileid=abc"));
    }
}
