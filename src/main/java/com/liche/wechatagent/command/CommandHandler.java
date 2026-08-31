package com.liche.wechatagent.command;

/** 斜杠指令处理器（全部走命令解析器，不经过大模型） */
public interface CommandHandler {

    /** 指令名（不含斜杠） */
    String name();

    /** 指令说明 */
    String description();

    /** 执行指令，返回回复文本 */
    String handle(String args, String userId);
}
