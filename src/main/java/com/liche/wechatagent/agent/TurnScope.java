package com.liche.wechatagent.agent;

import java.util.Set;

/**
 * 一轮对话的**作用域**（三期领域②：「它自己的时间」）。
 *
 * <p>为什么做成一个对象而不是继续加参数：作用域决定三件事——**哪些工具下发**、
 * **哪些工具即便被模型幻觉出来也不许执行**、**允许几轮工具循环**。分开传会一路加参数。
 *
 * @param providers   允许的 provider 简单类名；空集 = 不限（普通对话）
 * @param deniedTools 即便 provider 在名单里也**不许**下发的工具名。
 *                    "权限大一些"是让它更能做自己的事，**不是把不可逆的动作也交出去**
 *                    （例如给你的 QQ 发文件、删掉资料库里的东西）。
 * @param maxRounds   允许的工具轮数；0 = 用全局默认
 */
public record TurnScope(Set<String> providers, Set<String> deniedTools, int maxRounds) {

    /** 无作用域：全量工具、全局轮数（普通对话用） */
    public static final TurnScope ALL = new TurnScope(Set.of(), Set.of(), 0);

    public boolean isScoped() {
        return providers != null && !providers.isEmpty();
    }

    public boolean allowsProvider(String simpleClassName) {
        return !isScoped() || providers.contains(simpleClassName);
    }

    public boolean denies(String toolName) {
        return deniedTools != null && deniedTools.contains(toolName);
    }
}
