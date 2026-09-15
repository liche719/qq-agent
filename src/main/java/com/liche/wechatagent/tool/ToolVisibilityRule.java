package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Set;

/**
 * 「按用户作用域决定哪些工具不下发」的规则（2026-09-15 加）。
 *
 * <p><b>为什么要有这个接口</b>：工具集裁剪器原来**硬编码了自主模块的知识**——
 * 认 `selfQuest*` 前缀、还要 {@code import com.liche.wechatagent.self.SelfCoreService}
 * 来判断"现在是不是它自己的作用域"。于是**工具层反过来依赖业务模块**，方向是错的：
 * 拔掉模块，工具层还留着一处编译依赖；加第二个模块还得再改一次裁剪器。
 *
 * <p>现在反过来：模块自己声明规则（如 {@code SelfToolVisibilityRule}），裁剪器只负责**依次问**。
 * 模块关掉时它的规则 bean 不存在，这条规则自然消失——**裁剪器一行都不用改**。
 */
public interface ToolVisibilityRule {

    /** 排障用：日志里要能看出是哪条规则裁掉的 */
    String name();

    /**
     * 这个作用域下哪些工具不该下发。
     *
     * @param userId 当前作用域（可能是机主的 openid，也可能是模块自己的作用域）
     * @param all    当前全部候选工具
     * @return 要隐藏的工具名；没有就返回空集（不要返回 null）
     */
    Set<String> hiddenFor(String userId, List<ToolSpecification> all);
}
