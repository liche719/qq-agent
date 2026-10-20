package com.liche.wechatagent.self;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 它对自己的描述（**2026-10-20 起不再是表，是 {@link AgentSelfNote} 里 {@code kind=BLOCK} 的那几条**）。
 *
 * <p>每个 {@code blockType}（人设/任务/项目）只有一条"当前生效"的；改写时插新的一条、旧的标失效
 * ——**历史因此自带**，不用另存。
 *
 * <p>保留这个类型（而不是让调用方直接用新实体）是为了让 8 个读它的类**一行逻辑都不用改**：
 * 这次大改的风险因此被关在 {@link SelfCoreService} 一个类里。
 *
 * <p>{@code label / charLimit / description} 是早期设计里的修饰字段，合并后没有对应列，
 * 这里给默认值（实测这三个字段从来没被填过）。
 */
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfBlock {

    /** 我是谁 */
    public static final String TYPE_PERSONA = "PERSONA";
    /** 我现在在做什么 */
    public static final String TYPE_TASK = "TASK";
    /** 一个持续的项目 */
    public static final String TYPE_PROJECT = "PROJECT";
    /** 一贯的样子（倾向，程序按阈值提升后才写） */
    public static final String TYPE_STANCE = "STANCE";
    /** 随手记 */
    public static final String TYPE_NOTE = "NOTE";

    private Long id;

    private String blockType;

    private String label = "";

    private String value;

    private Integer charLimit = 1200;

    private String description = "";

    private Integer version = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
