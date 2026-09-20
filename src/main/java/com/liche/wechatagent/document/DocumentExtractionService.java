package com.liche.wechatagent.document;

import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.network.PublicUrlValidator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);
    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 8;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; WechatAgent/1.0)";

    private final OkHttpClient client;
    private final int maxFileBytes;
    private final int maxTextChars;
    private final int maxPdfPages;
    private final int maxRedirects;
    private final String userAgent;
    private final PublicUrlValidator urlValidator;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentExtractionService(@Value("${document.max-file-bytes:20971520}") int maxFileBytes,
                                     @Value("${document.max-text-chars:60000}") int maxTextChars,
                                     @Value("${document.max-pdf-pages:10}") int maxPdfPages,
                                     @Value("${document.max-redirects:3}") int maxRedirects,
                                     @Value("${document.connect-timeout-seconds:8}") long connectTimeoutSeconds,
                                     @Value("${document.read-timeout-seconds:30}") long readTimeoutSeconds,
                                     @Value("${document.user-agent:Mozilla/5.0 (compatible; WechatAgent/1.0)}") String userAgent,
                                     PublicUrlValidator urlValidator) {
        this.maxFileBytes = bounded(maxFileBytes, 1_024, Integer.MAX_VALUE, 20 * 1024 * 1024);
        this.maxTextChars = bounded(maxTextChars, 256, 1_000_000, 60_000);
        this.maxPdfPages = bounded(maxPdfPages, 1, 100, 10);
        this.maxRedirects = bounded(maxRedirects, 0, 10, DEFAULT_MAX_REDIRECTS);
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.trim();
        this.urlValidator = urlValidator;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(bounded(connectTimeoutSeconds, 1, 300, DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .readTimeout(Duration.ofSeconds(bounded(readTimeoutSeconds, 1, 600, DEFAULT_READ_TIMEOUT_SECONDS)))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    public DocumentExtractionService(int maxFileBytes, int maxTextChars, int maxPdfPages,
                                     PublicUrlValidator urlValidator) {
        this(maxFileBytes, maxTextChars, maxPdfPages, DEFAULT_MAX_REDIRECTS,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS, DEFAULT_USER_AGENT, urlValidator);
    }

    /** 批量读取结果：能读的进 documents，读不了的进 failures（**一个失败不再拖垮整批**） */
    public record ExtractionResult(List<ExtractedDocument> documents, List<String> failures) {
    }

    public ExtractionResult extractAll(List<InboundAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ExtractionResult(List.of(), List.of());
        }
        List<ExtractedDocument> documents = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (InboundAttachment attachment : attachments) {
            // 2026-09-20：以前这里是直接 `documents.add(extract(...))`，一个附件抛异常就整批发不出去
            // （用户发 4 个文件、其中 1 个是老 .ppt → 其余 3 个也读不了，只回一句错误）。
            // 现在逐个兜住，读不了的记进 failures，由 AgentOrchestrator 告诉模型"哪个文件没读成"。
            try {
                documents.add(extract(attachment));
            } catch (DocumentExtractionException exception) {
                String label = displayName(attachment, "（没有文件名）");
                failures.add(label + "：" + exception.getMessage());
                log.warn("附件读不了 name={} type={} host={} reason={}", label, mimeType(attachment),
                        hostOf(attachment.url()), exception.getMessage());
            }
        }
        return new ExtractionResult(List.copyOf(documents), List.copyOf(failures));
    }

    private ExtractedDocument extract(InboundAttachment attachment) {
        if (attachment.url() == null || attachment.url().isBlank()) {
            throw new DocumentExtractionException("文件没有可读取的下载地址。");
        }
        byte[] bytes = download(attachment.url());
        if (bytes.length == 0) {
            throw new DocumentExtractionException("下载到的内容是空的，请重新发一次。");
        }
        if (looksLikeHtml(bytes)) {
            // QQ 的文件地址有时会回一个登录/错误页（HTTP 200 但内容不是文件），这时要说清楚而不是"不支持该类型"
            throw new DocumentExtractionException("下载到的不是文件内容（拿到的是网页），请重新发一次这个文件。");
        }
        String name = displayName(attachment, "");
        if (isPdf(attachment, bytes)) {
            return extractPdf(name.isBlank() ? "document.pdf" : name, bytes);
        }
        if (isDocx(attachment, bytes)) {
            return extractDocx(name.isBlank() ? "document.docx" : name, bytes);
        }
        // 2026-09-20 加：把图片**当文件**发过来（QQ 的"文件"元素）时，原来会落到最后那句
        // "只支持 PDF 和 DOCX" 被拒——而同一张图用"图片"元素发却能看。这里按字节头认出图片后，
        // 走和扫描版 PDF 一样的 pageImages 通道交给视觉模型。
        if (sniffImageMime(bytes) != null) {
            return extractImage(name.isBlank() ? "image.jpg" : name, bytes);
        }
        if (isPptx(attachment, bytes)) {
            return extractPptx(name.isBlank() ? "slides.pptx" : name, bytes);
        }
        if (isLegacyPpt(bytes)) {
            return extractLegacyPpt(name.isBlank() ? "slides.ppt" : name, bytes);
        }
        log.warn("附件类型不认识 name={} type={} host={} 大小={} 头16字节={}", name, mimeType(attachment),
                hostOf(attachment.url()), bytes.length, hexHead(bytes, 16));
        throw new DocumentExtractionException("这个文件我暂时读不了（现在支持：图片 jpg/png/webp/gif、PDF、"
                + "Word .docx、PPT .ppt/.pptx）。如果确实需要，先另存为 PDF 再发我。");
    }

    /** 以文件形式发来的图片：字节头认出类型后直接给视觉模型看（大小已在 download() 里卡过） */
    private ExtractedDocument extractImage(String name, byte[] bytes) {
        String mime = sniffImageMime(bytes);
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return new ExtractedDocument(name, "这是用户发来的图片，已提供给视觉模型查看。", List.of(dataUrl), false);
    }

    /** 只认字节头，不认后缀/MIME：先把真正是图片的挑出来，避免把改名的怪文件塞给视觉模型 */
    private String sniffImageMime(byte[] bytes) {
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        byte[] gif = "GIF8".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (startsWith(bytes, gif)) {
            return "image/gif";
        }
        byte[] riff = "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] webp = "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (startsWith(bytes, riff) && matchesAt(bytes, 8, webp)) {
            return "image/webp";
        }
        return null;
    }

    private byte[] download(String url) {
        URI current;
        try {
            current = urlValidator.validate(url);
        } catch (IllegalArgumentException exception) {
            throw new DocumentExtractionException("文件下载地址无效或不安全，请重新发送文件。", exception);
        }
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            Request request = new Request.Builder().url(current.toString())
                    .header("User-Agent", userAgent)
                    .get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new DocumentExtractionException("文件下载跳转失败，请重新发送文件。");
                    }
                    try {
                        current = urlValidator.validate(current.resolve(location).toString());
                    } catch (IllegalArgumentException exception) {
                        throw new DocumentExtractionException("文件下载地址无效或不安全，请重新发送文件。", exception);
                    }
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new DocumentExtractionException("文件下载失败，请重新发送一次。");
                }
                long contentLength = response.body().contentLength();
                if (contentLength > maxFileBytes) {
                    throw new DocumentExtractionException("文件超过 " + maxFileBytes / 1024 / 1024 + " MB，暂不处理。");
                }
                try (var input = response.body().byteStream(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxFileBytes) {
                            throw new DocumentExtractionException("文件超过 " + maxFileBytes / 1024 / 1024 + " MB，暂不处理。");
                        }
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } catch (DocumentExtractionException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new DocumentExtractionException("文件下载失败，请重新发送一次。", exception);
            }
        }
        throw new DocumentExtractionException("文件跳转次数过多，请重新发送文件。");
    }

    private ExtractedDocument extractPdf(String name, byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document).trim();
            if (!text.isBlank()) {
                return new ExtractedDocument(name, limit(text), List.of(), text.length() > maxTextChars);
            }
            int pages = Math.min(document.getNumberOfPages(), maxPdfPages);
            if (pages == 0) {
                throw new DocumentExtractionException("这个 PDF 没有可识别的页面。");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<String> pageImages = new ArrayList<>();
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 150, ImageType.RGB);
                pageImages.add(toDataUrl(image));
            }
            String note = "这是扫描型 PDF，已提供前 " + pages + " 页页面图像供视觉模型阅读。";
            return new ExtractedDocument(name, note, pageImages, document.getNumberOfPages() > pages);
        } catch (DocumentExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentExtractionException("PDF 读取失败，请确认文件没有损坏或加密。", exception);
        }
    }

    private ExtractedDocument extractDocx(String name, byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText().trim();
            if (text.isBlank()) {
                throw new DocumentExtractionException("这个 Word 文档没有可读取的文字内容。");
            }
            return new ExtractedDocument(name, limit(text), List.of(), text.length() > maxTextChars);
        } catch (DocumentExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentExtractionException("Word 文档读取失败，请确认文件是未加密的 DOCX。", exception);
        }
    }

    private boolean isPdf(InboundAttachment attachment, byte[] bytes) {
        return (mimeType(attachment).equals("application/pdf") || fileName(attachment).endsWith(".pdf"))
                && startsWith(bytes, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private boolean isDocx(InboundAttachment attachment, byte[] bytes) {
        return (mimeType(attachment).equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || fileName(attachment).endsWith(".docx"))
                && startsWith(bytes, new byte[]{'P', 'K'})
                && hasZipContentType(bytes, "wordprocessingml.document.main+xml");
    }

    /** 2026-09-20 加：.pptx（OOXML）按 [Content_Types].xml 里的标记认，避免把 zip/docx 误判成 PPT */
    private boolean isPptx(InboundAttachment attachment, byte[] bytes) {
        return (mimeType(attachment)
                .equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || fileName(attachment).endsWith(".pptx"))
                && startsWith(bytes, new byte[]{'P', 'K'})
                && hasZipContentType(bytes, "presentationml.presentation.main+xml");
    }

    /** OOXML 都是 zip，靠 [Content_Types].xml 里的主类型标记区分 docx/pptx/xlsx */
    private boolean hasZipContentType(byte[] bytes, String marker) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    String types = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return types.contains(marker);
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    /** 逐页取文本框 / 表格里的文字（分组形状递归进去），带页码方便模型引用 */
    private ExtractedDocument extractPptx(String name, byte[] bytes) {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 0;
            for (XSLFSlide slide : show.getSlides()) {
                slideNumber++;
                StringBuilder slideText = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    appendShapeText(shape, slideText);
                }
                if (!slideText.isEmpty()) {
                    text.append("第 ").append(slideNumber).append(" 页：\n").append(slideText).append('\n');
                }
            }
            String content = text.toString().trim();
            if (content.isBlank()) {
                throw new DocumentExtractionException("这个 PPT 里没有可读取的文字（可能是整页图片），"
                        + "把关键几页截图发我我就能看。");
            }
            return new ExtractedDocument(name, limit(content), List.of(), content.length() > maxTextChars);
        } catch (DocumentExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentExtractionException("PPT 读取失败，请确认文件是未加密的 .pptx。", exception);
        }
    }

    private void appendShapeText(XSLFShape shape, StringBuilder out) {        if (shape instanceof XSLFTextShape textShape) {
            String value = textShape.getText();
            if (value != null && !value.isBlank()) {
                out.append(value.trim()).append('\n');
            }
            return;
        }
        if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                List<String> cells = new ArrayList<>();
                for (XSLFTableCell cell : row.getCells()) {
                    String value = cell.getText();
                    if (value != null && !value.isBlank()) {
                        cells.add(value.trim());
                    }
                }
                if (!cells.isEmpty()) {
                    out.append(String.join(" | ", cells)).append('\n');
                }
            }
            return;
        }
        if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                appendShapeText(child, out);
            }
        }
    }

    private String toDataUrl(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ---------- 老版二进制 .ppt（OLE2 / HSLF，2026-09-20 加：老师的课件常常就是 .ppt）----------

    /** OLE2 复合文档魔数；真正的 PPT 判定交给 HSLF 解析（能打开才算） */
    private boolean isLegacyPpt(byte[] bytes) {
        return matchesAt(bytes, 0, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
    }

    private ExtractedDocument extractLegacyPpt(String name, byte[] bytes) {
        try (HSLFSlideShow show = new HSLFSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 0;
            for (HSLFSlide slide : show.getSlides()) {
                slideNumber++;
                StringBuilder slideText = new StringBuilder();
                for (HSLFShape shape : slide.getShapes()) {
                    appendHslfShapeText(shape, slideText);
                }
                if (!slideText.isEmpty()) {
                    text.append("第 ").append(slideNumber).append(" 页：\n").append(slideText).append('\n');
                }
            }
            String content = text.toString().trim();
            if (content.isBlank()) {
                throw new DocumentExtractionException("这个 PPT 里没有可读取的文字（可能是整页图片），"
                        + "把关键几页截图发我我就能看。");
            }
            return new ExtractedDocument(name, limit(content), List.of(), content.length() > maxTextChars);
        } catch (DocumentExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            // OLE2 但不是 PPT（可能是 .doc/.xls）：交给上层给出"不支持"的提示
            throw new DocumentExtractionException("这个 OLE2 文档不是可读的 PPT（.doc/.xls 暂不支持），"
                    + "先另存为 .pptx 或 PDF 再发我。", exception);
        }
    }

    private void appendHslfShapeText(HSLFShape shape, StringBuilder out) {
        if (shape instanceof HSLFTextShape textShape) {
            String value = textShape.getText();
            if (value != null && !value.isBlank()) {
                out.append(value.trim()).append('\n');
            }
            return;
        }
        if (shape instanceof HSLFTable table) {
            int rows = table.getNumberOfRows();
            int columns = table.getNumberOfColumns();
            for (int row = 0; row < rows; row++) {
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    String value = table.getCell(row, column).getText();
                    if (value != null && !value.isBlank()) {
                        cells.add(value.trim());
                    }
                }
                if (!cells.isEmpty()) {
                    out.append(String.join(" | ", cells)).append('\n');
                }
            }
            return;
        }
        if (shape instanceof HSLFGroupShape group) {
            for (HSLFShape child : group.getShapes()) {
                appendHslfShapeText(child, out);
            }
        }
    }

    /** QQ 的文件地址失效时可能回一个 HTML 页面（HTTP 200），那要给"重新发一次"而不是"不支持该类型" */
    private boolean looksLikeHtml(byte[] bytes) {
        int limit = Math.min(bytes.length, 512);
        StringBuilder head = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            char value = (char) (bytes[index] & 0xFF);
            head.append(Character.isWhitespace(value) ? ' ' : Character.toLowerCase(value));
        }
        String text = head.toString().trim();
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<?xml");
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String hexHead(byte[] bytes, int count) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < Math.min(count, bytes.length); index++) {
            out.append(String.format("%02x", bytes[index]));
        }
        return out.toString();
    }

    private String limit(String text) {
        return text.length() <= maxTextChars ? text : text.substring(0, maxTextChars) + "\n[文档内容过长，已截断]";
    }

    private String displayName(InboundAttachment attachment, String fallback) {
        return attachment.name() == null || attachment.name().isBlank() ? fallback : attachment.name();
    }

    private String mimeType(InboundAttachment attachment) {
        return attachment.contentType() == null ? "" : attachment.contentType().toLowerCase(Locale.ROOT);
    }

    private String fileName(InboundAttachment attachment) {
        return attachment.name() == null ? "" : attachment.name().toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return matchesAt(value, 0, prefix);
    }

    private boolean matchesAt(byte[] value, int offset, byte[] expected) {
        if (value.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
