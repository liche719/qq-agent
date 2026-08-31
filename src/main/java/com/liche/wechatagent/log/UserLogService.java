package com.liche.wechatagent.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 运行日志按 user_id 隔离存储 + 结构化操作日志落库 */
@Service
public class UserLogService {

    private static final Logger log = LoggerFactory.getLogger(UserLogService.class);

    private final OperationLogRepository operationLogRepository;

    public UserLogService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public void record(String userId, String action, String detail) {
        try {
            operationLogRepository.save(new OperationLog(userId, action, detail));
        } catch (Exception e) {
            log.warn("写操作日志失败 userId={}", userId, e);
        }
        log.info("[{}] {} - {}", userId, action, detail);
    }

    public List<OperationLog> recent(String userId) {
        return operationLogRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
