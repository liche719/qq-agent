package com.liche.wechatagent.interview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面试陪练的一轮记录：题目、回答要点、四维评分、一句反馈。
 *
 * <p>评分由模型在每轮结束后通过工具写入，**复盘报告由程序根据这些数据生成**，
 * 不依赖模型记不记得自己给过几分。
 */
@Entity
@Table(name = "interview_round", indexes = {
        @Index(name = "idx_interview_user_session", columnList = "userId,sessionId")
})
@Getter
@Setter
@NoArgsConstructor
public class InterviewRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    /** 一次练习的标识：开始陪练时生成，退出时结束 */
    @Column(length = 40)
    private String sessionId;

    /** 本轮岗位（用户开始练习时说的，可为空） */
    @Column(length = 120)
    private String role;

    /** 第几轮，从 1 开始 */
    private Integer seq;

    /** 题类：自我介绍 / 项目深挖 / 技术基础 / 系统设计 / 行为面试 / 反问环节 */
    @Column(length = 32)
    private String category;

    @Column(length = 600)
    private String question;

    /** 用户回答的要点摘要（由模型压缩，不存原文） */
    @Column(length = 1200)
    private String answerSummary;

    /** 四维评分 1~5 */
    private Integer scoreContent;
    private Integer scoreStructure;
    private Integer scoreDepth;
    private Integer scoreDelivery;

    /** 本轮一句反馈（亮点 + 一个改法） */
    @Column(length = 800)
    private String feedback;

    private LocalDateTime createdAt;
}
