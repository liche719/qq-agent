package com.liche.wechatagent.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 运行日志按 user_id 隔离存储 + 结构化操作日志落库 */
@Service
public class UserLogService {

    private static final Logger log = LoggerFactory.getLogger(UserLogService.class);

    private final OperationLogRepository operationLogRepository;

    public UserLogService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public void record(String userId, String action) {
        record(userId, action, Map.of());
    }

    public void record(String userId, String action, Map<String, ?> metadata) {
        String detail = safeMetadata(metadata);
        try {
            operationLogRepository.save(new OperationLog(userId, action, detail));
        } catch (Exception e) {
            log.warn("写操作日志失败 userScope={} action={}", UserScope.forUser(userId), action, e);
        }
        log.info("operation userScope={} action={} metadata={}", UserScope.forUser(userId), action, detail);
    }

    public List<OperationLog> recent(String userId) {
        return operationLogRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    private String safeMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        return metadata.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + safeValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String safeValue(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof TemporalAccessor || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        return value == null ? "null" : "[redacted]";
    }
}
