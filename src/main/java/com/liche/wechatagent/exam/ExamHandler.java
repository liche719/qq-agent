package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「考研」入口：看备考计划 + 今天的安排。 */
@Component
public class ExamHandler implements CommandHandler {

    private final ExamService examService;

    public ExamHandler(ExamService examService) {
        this.examService = examService;
    }

    @Override
    public String name() {
        return "exam";
    }

    @Override
    public String description() {
        return "考研备考计划（科目/目标分/阶段）与今日安排";
    }

    @Override
    public List<String> aliases() {
        return List.of("考研", "考研计划", "备考计划", "考研目标");
    }

    @Override
    public String handle(String args, String userId) {
        String plan = examService.planText(userId);
        ExamPlan current = examService.plan(userId);
        if (current == null) {
            return plan;
        }
        return plan + "\n\n" + examService.todayText(userId);
    }
}
