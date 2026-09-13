package com.liche.wechatagent.media;

/**
 * 库里还留着记录，但磁盘上的文件已经没有了。
 *
 * <p>这是**确定性**失败：文件不会自己出现，重试只是白跑一轮（模型再等十几秒拿到同一个错误）。
 * 所以媒体工具把这种情况转成 {@code ToolBusinessResult.failure(...)}（不可重试），
 * 而不是抛出去被工具框架当成瞬时故障重试。
 */
public class MediaSourceMissingException extends RuntimeException {

    public MediaSourceMissingException(String message) {
        super(message);
    }
}
