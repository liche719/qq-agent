package com.liche.wechatagent.exception;

/** 业务异常：携带面向用户的友好提示，不暴露技术细节 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }
}
