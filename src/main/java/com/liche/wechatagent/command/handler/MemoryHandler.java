package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.memory.MemoryManagementService;
import org.springframework.stereotype.Component;

@Component
public class MemoryHandler implements CommandHandler {

    private final MemoryManagementService memoryManagementService;

    public MemoryHandler(MemoryManagementService memoryManagementService) {
        this.memoryManagementService = memoryManagementService;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "查看、开关或删除自动记忆";
    }

    @Override
    public String handle(String args, String userId) {
        try {
            return memoryManagementService.handle(userId, args);
        } catch (BizException exception) {
            return exception.getMessage();
        }
    }
}
