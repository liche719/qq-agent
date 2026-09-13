package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「错题本」入口：
 * 「错题本」= 看今天要回收什么；「错题 快排最坏复杂度推导」= 直接记一条。
 */
@Component
public class ExamMistakeHandler implements CommandHandler {

    private final ExamTrackService trackService;

    public ExamMistakeHandler(ExamTrackService trackService) {
        this.trackService = trackService;
    }

    @Override
    public String name() {
        return "exam-mistakes";
    }

    @Override
    public String description() {
        return "错题本（直接发「错题 内容」记一条，发「错题本」看今天要复习什么）";
    }

    @Override
    public List<String> aliases() {
        return List.of("错题本", "我的错题", "查看错题", "错题");
    }

    @Override
    public String handle(String args, String userId) {
        if (args == null || args.isBlank()) {
            return trackService.mistakesText(userId);
        }
        return trackService.addMistake(userId, null, args, null, null);
    }
}
