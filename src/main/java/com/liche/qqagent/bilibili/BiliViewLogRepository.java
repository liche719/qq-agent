package com.liche.qqagent.bilibili;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 「它看过哪些视频」的读写（只追加，按 id 倒序即时间倒序） */
public interface BiliViewLogRepository extends JpaRepository<BiliViewLog, Long> {

    List<BiliViewLog> findTop100ByOrderByIdDesc();

    /** 按关键词找它看过的视频（标题/摘要/看法都匹配，最近的在前） */
    List<BiliViewLog> findTop30ByTitleContainingIgnoreCaseOrSummaryContainingIgnoreCaseOrTakeawayContainingIgnoreCaseOrderByIdDesc(
            String title, String summary, String takeaway);

    /** 同一个视频最近那条（记摘要时覆盖它，不重复插行） */
    List<BiliViewLog> findByBvidOrderByIdDesc(String bvid, org.springframework.data.domain.Pageable pageable);

    /**
     * 取某个视频**最近一条**台账（2026-10-21 加）。
     *
     * <p>为什么不再用带 Pageable 的那个：实测 noteWatchedVideo 没能命中已有行——
     * 同一个视频被读三次就插了三条台账（outcome 分别是看了画面/字幕/看了画面）。
     * findFirst 的语义没有翻页余地，返回 Optional 也逼着调用方处理"没有"的情况。
     */
    java.util.Optional<BiliViewLog> findFirstByBvidOrderByIdDesc(String bvid);
}
