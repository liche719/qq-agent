package com.liche.wechatagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);

    private final WorkMemoryService workMemoryService;
    private final ConversationMemoryService conversationMemoryService;

    @Autowired
    public MemoryLifecycleService(WorkMemoryService workMemoryService,
                                 ConversationMemoryService conversationMemoryService) {
        this.workMemoryService = workMemoryService;
        this.conversationMemoryService = conversationMemoryService;
    }

    public MemoryLifecycleService(WorkMemoryService workMemoryService) {
        this(workMemoryService, null);
    }

    @Scheduled(fixedDelayString = "${memory.lifecycle-scan-interval-ms:3600000}", initialDelayString = "${memory.lifecycle-initial-delay-ms:60000}")
    public void expireDueMemories() {
        try {
            int expired = workMemoryService.expireDueMemories();
            if (expired > 0) {
                log.info("记忆生命周期扫描完成，自动过期 {} 条工作记忆", expired);
            }
        } catch (Exception exception) {
            log.warn("工作记忆生命周期扫描失败 reason={}", exception.getClass().getSimpleName(), exception);
        }
        if (conversationMemoryService != null) {
            try {
                int purged = conversationMemoryService.purgeExpired();
                if (purged > 0) {
                    log.info("对话证据生命周期扫描完成，清理 {} 条过期记录", purged);
                }
            } catch (Exception exception) {
                log.warn("对话证据生命周期扫描失败 reason={}", exception.getClass().getSimpleName(), exception);
            }
        }
    }
}
