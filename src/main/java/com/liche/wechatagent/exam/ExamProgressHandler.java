package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「考研进度」入口：今日完成率、近 7 天、连续打卡、最弱科目、遗留任务。 */
@Component
public class ExamProgressHandler implements CommandHandler {

    private final ExamService examService;

    public ExamProgressHandler(ExamService examService) {
        this.examService = examService;
    }

    @Override
    public String name() {
        return "exam-progress";
    }

    @Override
    public String description() {
        return "考研进度（今日/近 7 天完成率、连续打卡、最弱科目）";
    }

    @Override
    public List<String> aliases() {
        return List.of("考研进度", "复习进度", "考研情况", "我的考研进度");
    }

    @Override
    public String handle(String args, String userId) {
        return examService.progressText(userId);
    }
}
