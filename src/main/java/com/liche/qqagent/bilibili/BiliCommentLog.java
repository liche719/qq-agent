package com.liche.qqagent.bilibili;

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
 * 一条 B 站评论的台账（2026-10-20 加，DDL 见 {@code deploy/postgres/V21__create_bili_comment_log.sql}）。
 *
 * <p>用户要的是"笨鱼自己刷视频、自己发评论，内容也记在某个地方"。所以这张表有两个用途：
 * <b>记录</b>（面板能翻、出问题能查）和<b>护栏的判定依据</b>（每天几条、这个视频评过没、距上条多久）。
 * 护栏**从表里算**，不靠模型自觉。
 */
@Entity
@Table(name = "bili_comment_log")
@Getter
@Setter
@NoArgsConstructor
public class BiliCommentLog {

    public static final int RESULT_OK = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bvid", length = 32, nullable = false)
    private String bvid = "";

    @Column(name = "aid", nullable = false)
    private long aid;

    @Column(name = "video_title", length = 256, nullable = false)
    private String videoTitle = "";

    @Column(name = "message", nullable = false)
    private String message = "";

    /** 回复楼中楼时的根评论；0 表示顶级评论 */
    @Column(name = "root_rpid", nullable = false)
    private long rootRpid;

    @Column(name = "parent_rpid", nullable = false)
    private long parentRpid;

    /** B 站返回的业务码，0 = 成功 */
    @Column(name = "result_code", nullable = false)
    private int resultCode = -1;

    @Column(name = "result_message", length = 256, nullable = false)
    private String resultMessage = "";

    @Column(name = "rpid", nullable = false)
    private long rpid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean succeeded() {
        return resultCode == RESULT_OK;
    }
}
