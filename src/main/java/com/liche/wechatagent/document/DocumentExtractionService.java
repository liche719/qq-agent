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

    private static final int MAX_REDIRECTS = 3;

    private final OkHttpClient client;
    private final int maxFileBytes;
    private final int maxTextChars;
    private final int maxPdfPages;
    private final PublicUrlValidator urlValidator;

    public DocumentExtractionService(@Value("${document.max-file-bytes:20971520}") int maxFileBytes,
                                     @Value("${document.max-text-chars:60000}") int maxTextChars,
                                     @Value("${document.max-pdf-pages:10}") int maxPdfPages,
                                     PublicUrlValidator urlValidator) {
        this.maxFileBytes = maxFileBytes;
        this.maxTextChars = maxTextChars;
        this.maxPdfPages = maxPdfPages;
        this.urlValidator = urlValidator;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(30))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    public List<ExtractedDocument> extractAll(List<InboundAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ExtractedDocument> documents = new ArrayList<>();
        for (InboundAttachment attachment : attachments) {
            documents.add(extract(attachment));
        }
        return documents;
    }

    private ExtractedDocument extract(InboundAttachment attachment) {
        if (attachment.url() == null || attachment.url().isBlank()) {
            throw new DocumentExtractionException("文件没有可读取的下载地址。");
        }
        byte[] bytes = download(attachment.url());
        if (isPdf(attachment, bytes)) {
            return extractPdf(displayName(attachment, "document.pdf"), bytes);
        }
        if (isDocx(attachment, bytes)) {
            return extractDocx(displayName(attachment, "document.docx"), bytes);
        }
        throw new DocumentExtractionException("目前只支持 PDF 和 DOCX 文件。");
    }

    private byte[] download(String url) {
        URI current;
        try {
            current = urlValidator.validate(url);
        } catch (IllegalArgumentException exception) {
            throw new DocumentExtractionException("文件下载地址无效或不安全，请重新发送文件。", exception);
        }
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            Request request = new Request.Builder().url(current.toString()).get().build();
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
                && hasDocxContentType(bytes);
    }

    private boolean hasDocxContentType(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    String types = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return types.contains("wordprocessingml.document.main+xml");
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private String toDataUrl(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
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
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
