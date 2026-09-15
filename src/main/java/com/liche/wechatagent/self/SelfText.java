package com.liche.wechatagent.self;

/**
 * 它自己那侧的文本小工具：**截断一律为省略号留一位**。
 *
 * <p>坑 40：{@code substring(0, max) + "…"} 会多出 1 个字符，撞上列宽就是
 * {@code Data too long}——整条写入失败、而且报警的是别的地方。所以只留这一处实现。
 */
public final class SelfText {

    private SelfText() {
    }

    /** 按列宽截断（保留换行：多行正文进列时用这个） */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    /** 按列宽截断，并把换行压成空格（进提示词/日志的单行摘要用这个） */
    public static String clipLine(String text, int max) {
        return clip(text == null ? null : text.replace('\n', ' '), max);
    }

    /** 可空计数当 0（它自己的计数列允许为空） */
    public static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** null 或全空白 */
    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
