package com.liche.wechatagent.self;

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
 * 一条教训（三期领域①「它自己的可靠性」，spec §9.1）。
 *
 * <p>它维护的是**它自己反复在哪栽**（时间算错、承诺忘收尾、事实性瞎猜、格式、工具用法），
 * 并自己给自己补课——标尺是**同类错误复现率下降**（§9.2），不是"看起来在成长"。
 *
 * <p>为什么三段必须齐：只有「我做了什么 / 我当时预期 / 实际发生了什么」都写清楚，
 * `correction` 才可能是**可执行的**；否则就是写感悟（"以后要更细心"），那种句子没用。
 *
 * <p>为什么模型不能自己点赞：UPVOTE（没再犯）/ DOWNVOTE（又犯了）**只能来自后续真实事件**，
 * 由程序判定（复查时该类别有没有新教训 / 同类再次发生）。让模型自评就是自我暗示。
 */
@Entity
@Table(name = "agent_lesson", indexes = {
        @Index(name = "idx_self_lesson_status", columnList = "status,next_review_at"),
        @Index(name = "idx_self_lesson_category", columnList = "category,status,last_seen_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentLesson {

    /** 时间/日程计算 */
    public static final String CATEGORY_TIME = "TIME";
    /** 承诺收尾 */
    public static final String CATEGORY_COMMITMENT = "COMMITMENT";
    /** 事实性瞎猜（没查就答） */
    public static final String CATEGORY_GUESS = "GUESS";
    /** 格式/表达 */
    public static final String CATEGORY_FORMAT = "FORMAT";
    /** 工具用法 */
    public static final String CATEGORY_TOOL = "TOOL";

    public static final String TRIGGER_SURPRISE = "SURPRISE";
    public static final String TRIGGER_USER_POINTED = "USER_POINTED";
    public static final String TRIGGER_PROMISE_BROKEN = "PROMISE_BROKEN";
    public static final String TRIGGER_SELF_CHECK = "SELF_CHECK";

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_IMPROVING = "improving";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 24, nullable = false)
    private String category;

    /** 怎么发现的：SURPRISE（意外度）/ USER_POINTED（用户指出）/ PROMISE_BROKEN / SELF_CHECK */
    @Column(name = "trigger_type", length = 24, nullable = false)
    private String triggerType;

    @Column(name = "what_i_did", length = 500, nullable = false)
    private String whatIDid;

    @Column(name = "expected_result", length = 500, nullable = false)
    private String expectedResult;

    @Column(name = "what_happened", length = 500, nullable = false)
    private String whatHappened;

    /** 以后怎么做：可执行的短句 */
    @Column(length = 500, nullable = false)
    private String correction;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "recurrence_count", nullable = false)
    private Integer recurrenceCount = 1;

    /** 复查时"没再犯"的连续次数（≥3 → closed） */
    @Column(name = "clean_reviews", nullable = false)
    private Integer cleanReviews = 0;

    @Column(nullable = false)
    private Double stability = 1.0;

    @Column(nullable = false)
    private Double difficulty = 5.0;

    private LocalDateTime lastReviewAt;
    private LocalDateTime nextReviewAt;

    @Column(length = 16, nullable = false)
    private String status = STATUS_OPEN;

    @Column(length = 300)
    private String evidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 该翻出来复查了吗 */
    public boolean isDue(LocalDateTime now) {
        return nextReviewAt != null && !nextReviewAt.isAfter(now) && !STATUS_CLOSED.equals(status);
    }
}
