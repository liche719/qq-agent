package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「结束学习」入口：把这一段计时记进当天打卡。 */
@Component
public class ExamStudyEndHandler implements CommandHandler {

    private final ExamTrackService trackService;
    private final ExamService examService;

    public ExamStudyEndHandler(ExamTrackService trackService, ExamService examService) {
        this.trackService = trackService;
        this.examService = examService;
    }

    @Override
    public String name() {
        return "exam-study-end";
    }

    @Override
    public String description() {
        return "结束学习计时并记进当天打卡";
    }

    @Override
    public List<String> aliases() {
        return List.of("结束学习", "结束计时", "学完了", "计时结束");
    }

    @Override
    public String handle(String args, String userId) {
        ExamTrackService.StudyStop stop = trackService.stopStudy(userId);
        if (stop == null) {
            return "现在没有在计时的学习段。想直接记时长就说「打卡 150」（分钟），或者先说「开始学XX」。";
        }
        return "⏱「" + stop.subject() + "」记了 " + stop.minutes() + " 分钟。\n"
                + examService.checkin(userId, (int) stop.minutes(), "计时：" + stop.subject());
    }
}
