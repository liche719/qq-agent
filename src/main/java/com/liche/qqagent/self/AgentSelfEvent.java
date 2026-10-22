package com.liche.qqagent.self;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 它自己那侧的一条流水（**2026-10-20 起不再是表，是 {@link AgentSelfNote} 的视图**）。
 *
 * <p>为什么不直接换成新实体：有 8 个类在读这些字段（面板、注入、倾向、反思），
 * 保留这个类型能让它们**一行逻辑都不用改**——真正换掉的只是它的存储（{@code agent_self_note}）。
 * 这样这次"大改"的风险被关在 {@link SelfCoreService} 一个类里。
 *
 * <p>另外：目标（GOAL_*）与承诺（COMMIT_*）两类**已整块删除**（0 次调用、0 行数据，
 * 语义被"开方向 + 下一步"覆盖），所以对应的常量也一起没了。
 */
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfEvent {

    /** 一轮里做出的判断（按 topic+stance 累积成倾向） */
    public static final String KIND_JUDGE = "JUDGE";
    /** 与用户意见相左（默认只讲一次，记一笔） */
    public static final String KIND_DISAGREE = "DISAGREE";
    public static final String KIND_REFLECT = "REFLECT";
    /** 倾向形成 / 修订 / 退役：**由程序按规则写**，模型没有直接写倾向的工具 */
    public static final String KIND_STANCE_FORMED = "STANCE_FORMED";
    public static final String KIND_STANCE_REVISED = "STANCE_REVISED";
    public static final String KIND_STANCE_RETIRED = "STANCE_RETIRED";
    /** 记了一条教训 / 又犯了一次（投票由程序判定，模型不能自己点赞） */
    public static final String KIND_LESSON = "LESSON";
    public static final String KIND_NOTE = "NOTE";

    /** 它自己的方向（它想去弄明白的东西，不是机主派的任务） */
    public static final String KIND_QUEST_OPENED = "QUEST_OPENED";
    public static final String KIND_QUEST_STEP = "QUEST_STEP";
    public static final String KIND_QUEST_NOTE = "QUEST_NOTE";
    public static final String KIND_QUEST_RETRACT = "QUEST_RETRACT";
    public static final String KIND_QUEST_CLOSED = "QUEST_CLOSED";
    /** 它自己决定"今天先到这" */
    public static final String KIND_QUEST_REST = "QUEST_REST";

    private Long id;

    private String kind;

    /** 判断的类别（如"学习安排 / 我自己的能力"），倾向提升按它归类 */
    private String topic;

    /** 立场（A / B / 中立） */
    private String stance;

    private String content;

    /** 证据：引用的对话记录 id 或事件 id（写入时校验真实性） */
    private String evidence;

    private Integer importance = 0;

    private LocalDateTime createdAt;
}
