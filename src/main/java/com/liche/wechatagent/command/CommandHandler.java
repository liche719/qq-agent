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

    /**
     * 是否只认「整串等于别名」，不要"首词命中 + 余下当参数"的兜底。
     *
     * <p>默认 false：像「打卡 150」「今日任务 明天」这种"指令 + 参数"要靠首词兜底。
     * 但有的词天然会被用户接着写内容——例如「考研 2026-12-20 报考XX大学 计算机」，
     * 若按首词命中就会**把后面的信息全吞掉**（只回一份计划概览，模型根本没机会解析）。
     * 这类入口设成 {@code true}，带参数的整句就会落到大模型，由模型调工具处理。
     */
    default boolean exactOnly() {
        return false;
    }

    /** 执行指令，返回回复文本 */
    String handle(String args, String userId);
}
