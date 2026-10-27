package com.liche.qqagent.channel.qq;

/**
 * 纯文本判断（2026-10-27 从 {@code QqChannel} 拆出，第 6 步的第一个小片）。
 *
 * <p>为什么先拆这个：它是发送路径里**唯一没有副作用**的部分——决定"这条消息用 markdown 还是纯文本发"。
 * 单独成类后才能写单测（以前只能靠真机看排版对不对）。
 *
 * <p>判据与拆之前逐字一致：含至少两行"像 markdown 的结构"（标题/列表/编号/加粗/行内代码）才按 markdown 发；
 * 长度超过上限一律退回纯文本（超长 markdown 在 QQ 里会渲染失败）。带工具尾注的消息固定走 markdown。
 */
final class QqTexts {

    private QqTexts() {
    }

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
}
