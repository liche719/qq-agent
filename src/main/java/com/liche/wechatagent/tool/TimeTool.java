package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 当前时间工具：LLM 需要知道准确日期/星期/时间时调用（无参）。
 * 通过工具获取时间，避免把动态时间写进系统提示词破坏 DeepSeek 前缀缓存。
 */
@Component
public class TimeTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm");

    @Tool("获取当前准确的日期、星期和具体时间（含时区），需要时间信息时调用")
    public String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return "当前时间：" + now.format(FMT);
    }
}
