package com.liche.wechatagent.bilibili;

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
}
