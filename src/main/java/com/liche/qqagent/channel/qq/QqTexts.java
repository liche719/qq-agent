package com.liche.qqagent.channel.qq;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ 文本处理的纯函数（2026-10-27 从 {@code QqChannel} 拆出，第 6 步）。
 *
 * <p>这里只放**没有副作用、可以单测**的判断与切分：排版方式（markdown / 纯文本）、长消息分页。
 * 发送动作本身仍留在 channel（要令牌、要 seq、要处理被动/主动降级）。
 */
final class QqTexts {

    private QqTexts() {
    }

    /**
     * 这条消息该不该按 markdown 发。
     *
     * <p>判据与拆之前逐字一致：含至少两行"像 markdown 的结构"（标题/列表/编号/加粗/行内代码）才按 markdown；
     * 超过长度上限一律退回纯文本（超长 markdown 在 QQ 里渲染容易失败）；带工具尾注的消息固定走 markdown。
     */
    static boolean markdownLike(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (maxChars > 0 && text.length() > maxChars) {
            return false;
        }
        String[] lines = text.split("\\r?\\n");
        int md = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.startsWith("*")
                    || trimmed.startsWith(">")) {
                md++;
            } else if (trimmed.matches("^\\d+\\.\\s.*")) {
                md++;
            } else if (trimmed.contains("**") || trimmed.contains("`")) {
                md++;
            }
        }
        return md >= 2 || text.contains("> _调用工具：");
    }

    /**
     * 按长度切分长消息（不带页码）。
     *
     * <p>规则与拆之前逐字一致：优先在**换行**处断，但断点必须超过半页（否则宁可硬切，
     * 避免出现一堆极短的页）；空白页丢掉。
     *
     * @param limit 单页上限，至少 1
     */
    static List<String> splitForSending(String text, int limit) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int pageLimit = Math.max(1, limit);
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + pageLimit);
            if (end < text.length()) {
                int breakAt = Math.max(offset + 1, text.lastIndexOf('\n', end));
                if (breakAt > offset + pageLimit / 2) {
                    end = breakAt;
                }
            }
            String part = text.substring(offset, end).trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
            offset = end;
        }
        return parts;
    }

    /** 给多页加 `[i/n]` 页码前缀（只有一页时也加，调用方按需取第一页） */
    static List<String> labelPages(List<String> parts) {
        List<String> labeled = new ArrayList<>();
        if (parts == null) {
            return labeled;
        }
        for (int index = 0; index < parts.size(); index++) {
            labeled.add("[" + (index + 1) + "/" + parts.size() + "]\n" + parts.get(index));
        }
        return labeled;
    }

    /**
     * 上传文件时 QQ 的 file_type：1 图片 / 2 视频 / 3 语音 / 4 其它（2026-10-27 从 channel 搬来）。
     *
     * <p>判错会导致图片被当成"文件"发出去、或者语音发不出来，所以单独抽出来测。
     */
    static int fileType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.startsWith("image/png") || normalized.startsWith("image/jpeg")) return 1;
        if (normalized.startsWith("video/mp4")) return 2;
        if (normalized.startsWith("audio/") || normalized.contains("silk")) return 3;
        return 4;
    }
}
