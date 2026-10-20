package com.liche.wechatagent.bilibili;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** 评论台账的查询入口；护栏那几个判断都在这里，避免散在工具里 */
public interface BiliCommentLogRepository extends JpaRepository<BiliCommentLog, Long> {

    /** 某个时间点之后成功发出去了几条（每天的额度按它算） */
    @Query("select count(l) from BiliCommentLog l where l.createdAt >= :since and l.resultCode = 0")
    long countSucceededSince(@Param("since") LocalDateTime since);

    /** 这个视频评过没有（只算成功的） */
    @Query("select count(l) from BiliCommentLog l where l.bvid = :bvid and l.resultCode = 0")
    long countSucceededForVideo(@Param("bvid") String bvid);

    /** 最后一条成功的评论时间（冷却间隔按它算） */
    @Query("select max(l.createdAt) from BiliCommentLog l where l.resultCode = 0")
    Optional<LocalDateTime> lastSucceededAt();

    /** 它自己发成功、且拿得到 rpid 的评论（最近的在前）——拉回复时用 */
    List<BiliCommentLog> findTop20ByResultCodeAndRpidGreaterThanOrderByIdDesc(int resultCode, long rpid);

    /** 这条回复是不是已经回过了（按对方那条回复的 rpid 判重） */
    boolean existsByParentRpidAndResultCode(long parentRpid, int resultCode);
}
