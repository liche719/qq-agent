package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.care.ProactiveCareService;
import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

@Component
public class CareHandler implements CommandHandler {

    private final ProactiveCareService proactiveCareService;

    public CareHandler(ProactiveCareService proactiveCareService) {
        this.proactiveCareService = proactiveCareService;
    }

    @Override
    public String name() {
        return "care";
    }

    @Override
    public String description() {
        return "管理每天或每周的低打扰目标复盘";
    }

    @Override
    public String handle(String args, String userId) {
        return proactiveCareService.configure(userId, args);
    }
}
