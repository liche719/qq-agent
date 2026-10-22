package com.liche.qqagent.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 后台任务的读写（2026-10-21） */
public interface BackgroundTaskRepository extends JpaRepository<BackgroundTask, Long> {

    /** 启动时要把这两类捞出来：排着的接着排，跑到一半的要标失败并告诉用户 */
    List<BackgroundTask> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    List<BackgroundTask> findByUserIdOrderByIdDesc(String userId);
}
