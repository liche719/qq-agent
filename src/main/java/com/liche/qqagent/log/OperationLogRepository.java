package com.liche.qqagent.log;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    List<OperationLog> findTop100ByOrderByCreatedAtDesc();

    List<OperationLog> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
