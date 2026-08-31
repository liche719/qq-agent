package com.liche.wechatagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);

    private final WorkMemoryService workMemoryService;

    public MemoryLifecycleService(WorkMemoryService workMemoryService) {
        this.workMemoryService = workMemoryService;
    }

    @Scheduled(fixedDelayString = "${memory.lifecycle-scan-interval-ms:3600000}", initialDelayString = "${memory.lifecycle-initial-delay-ms:60000}")
    public void expireDueMemories() {
        int expired = workMemoryService.expireDueMemories();
        if (expired > 0) {
            log.info("记忆生命周期扫描完成，自动过期 {} 条工作记忆", expired);
        }
    }
}
