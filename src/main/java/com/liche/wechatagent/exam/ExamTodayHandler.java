package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「今日任务」入口：没有任务就先按计划生成，再列出来。 */
@Component
public class ExamTodayHandler implements CommandHandler {

    private final ExamService examService;

    public ExamTodayHandler(ExamService examService) {
        this.examService = examService;
    }

    @Override
    public String name() {
        return "exam-today";
    }

    @Override
    public String description() {
        return "今天的考研任务（没有就按计划生成）";
    }

    @Override
    public List<String> aliases() {
        return List.of("今日任务", "今天的任务", "今天学什么", "生成今天的考研任务", "今天的考研任务");
    }

    @Override
    public String handle(String args, String userId) {
        if (examService.tasksOn(userId, examService.today()).isEmpty()) {
            int created = examService.generateTodayTasks(userId);
            if (created > 0) {
                return "已按计划排好 " + created + " 项。\n" + examService.todayText(userId);
            }
        }
        return examService.tasksText(userId, args);
    }
}
