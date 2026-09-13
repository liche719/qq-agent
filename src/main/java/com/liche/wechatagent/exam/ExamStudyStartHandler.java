package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「开始学习」入口：开始一段计时（学完说「结束学习」会把时长记进当天打卡）。 */
@Component
public class ExamStudyStartHandler implements CommandHandler {

    private final ExamTrackService trackService;

    public ExamStudyStartHandler(ExamTrackService trackService) {
        this.trackService = trackService;
    }

    @Override
    public String name() {
        return "exam-study-start";
    }

    @Override
    public String description() {
        return "开始学习计时（可带科目，例如「开始学数学」）";
    }

    @Override
    public List<String> aliases() {
        return List.of("开始学习", "开始学", "开始计时", "计时开始");
    }

    @Override
    public String handle(String args, String userId) {
        return trackService.startStudy(userId, args);
    }
}
