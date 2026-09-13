package com.liche.wechatagent.command;

import java.util.List;

/** 斜杠指令处理器（全部走命令解析器，不经过大模型） */
public interface CommandHandler {

    /** 指令名（不含斜杠） */
    String name();

    /** 指令说明 */
    String description();

    /**
     * 中文文本别名（不带斜杠，整串匹配）。
     *
     * <p>以前所有别名都堆在 {@link CommandRegistry} 的静态字典里，于是**新模块想加一句中文入口，
     * 就得改别人的公共类**。现在处理器可以自己声明别名，{@code CommandRegistry} 启动时合并——
     * 模块的中文入口跟模块代码放一起，加模块不用碰公共文件。
     */
    default List<String> aliases() {
        return List.of();
    }

    /** 执行指令，返回回复文本 */
    String handle(String args, String userId);
}
