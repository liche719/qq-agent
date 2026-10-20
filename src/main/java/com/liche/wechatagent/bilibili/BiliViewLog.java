package com.liche.wechatagent.bilibili;

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
 * 它读过的一个 B 站视频（2026-10-20 加，DDL 见 {@code deploy/postgres/V25__create_bili_view_log.sql}）。
 *
 * <p>用户要的是"**不干扰它，但我得知道它做了什么**"。这张表由工具埋点写入，
 * 不靠模型自述——它总结得含糊也不影响这里的准确性。
 *
 * <p>和 {@link BiliCommentLog} 分工：这张记"看了什么"，那张记"说了什么"。
 */
@Entity
@Table(name = "bili_view_log")
@Getter
@Setter
@NoArgsConstructor
public class BiliViewLog {

    public static final String OUTCOME_TRANSCRIPT = "字幕";
    public static final String OUTCOME_FRAMES = "抽帧";
    public static final String OUTCOME_FAILED = "读不到";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bvid", length = 32, nullable = false)
    private String bvid = "";

    @Column(name = "title", length = 256, nullable = false)
    private String title = "";

    /** 字幕 / 抽帧 / 读不到 */
    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome = "";

    /** 字数或帧数这类细节 */
    @Column(name = "detail", length = 64, nullable = false)
    private String detail = "";

    /**
     * **这视频讲什么**（2026-10-20 加）：它读完自己写的两三句。
     *
     * <p>没有这一列的时候台账只有"字幕 266 字"这种字数——过两天它想找
     * "那个讲 git 记忆的视频"就只能靠标题猜，等于没记住。
     */
    @Column(name = "summary", nullable = false)
    private String summary = "";

    /** 它自己的看法：为什么值得记、跟自己有什么关系（一句话） */
    @Column(name = "takeaway", length = 200, nullable = false)
    private String takeaway = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
