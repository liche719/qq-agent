package com.liche.wechatagent.bilibili;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 「它看过哪些视频」的读写（只追加，按 id 倒序即时间倒序） */
public interface BiliViewLogRepository extends JpaRepository<BiliViewLog, Long> {

    List<BiliViewLog> findTop100ByOrderByIdDesc();
}
