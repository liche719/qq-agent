package com.liche.wechatagent.self;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 它自己的一个「念头」（2026-10-20 加，DDL 见 {@code deploy/postgres/V22__create_agent_seed.sql}）。
 *
 * <p>为什么要有它：用户要的是"能力相互知道、相辅相成"——刷视频产生想法，想法驱动研究，
 * 研究又生出新的疑点，再去定向找资料。现有的 {@link AgentQuest} 太重（要有题目、要推进），
 * {@link AgentSelfEvent} 只是流水账，中间缺一个**轻量、可传递**的东西，就是它。
 *
 * <p>{@code OPEN} 的念头会在每轮注入到它自己的提示词里（{@link SelfLoader}），
 * 于是下一个活动——不管是研究还是刷视频——都能接着它往下走。
 */
@Entity
@Table(name = "agent_seed")
@Getter
@Setter
@NoArgsConstructor
public class AgentSeed {

    /** 还没消化 */
    public static final String STATUS_OPEN = "OPEN";
    /** 已经搞清了 */
    public static final String STATUS_DONE = "DONE";
    /** 不做了 */
    public static final String STATUS_DROPPED = "DROPPED";
    /** 升格成了一个方向 */
    public static final String STATUS_QUEST = "QUEST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一句话：我想搞清楚什么、或者想干点什么 */
    @Column(name = "content", nullable = false)
    private String content = "";

    /** 从哪来的（bilibili / research / reflection / 自己想到的…） */
    @Column(name = "source", length = 64, nullable = false)
    private String source = "";

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_OPEN;

    /** 升格成方向时记下 quest id */
    @Column(name = "quest_id", nullable = false)
    private long questId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public boolean open() {
        return STATUS_OPEN.equals(status);
    }
}
