package com.liche.wechatagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 全局异常拦截器：所有系统异常统一转化为自然语言友好提示，不暴露堆栈与技术参数 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, String>> handleBiz(BizException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    /** 资源不存在（如访问已移除的页面，或未启用对应模式时访问 /api/sim/*）→ 返回 404 友好提示，不记 ERROR */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Map.of("message",
                "请求的资源不存在：请确认访问路径，或该功能未在当前模式下启用"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(500).body(Map.of("message", "出错了，请稍后再试。"));
    }
}
