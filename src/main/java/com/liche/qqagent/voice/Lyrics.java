package com.liche.qqagent.voice;

/** 歌词文本的小处理（2026-11-03）。 */
final class Lyrics {

    private Lyrics() {
    }

    /** 清掉多余空行与首尾空白，保留 [verse]/[chorus] 这类结构标记 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /** 变成能念的句子：去掉结构标记，把换行变成句号，方便 TTS 念 */
    static String speakable(String raw) {
        String text = clean(raw)
                .replaceAll("\\[[^]]{1,12}]", "")
                .replaceAll("\\s*\\n+\\s*", "，")
                .trim();
        text = text.replaceAll("[，,]{2,}", "，");
        return text;
    }
}
