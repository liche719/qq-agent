package com.liche.wechatagent.exam;

import com.liche.wechatagent.command.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** 「打卡」入口：打卡 150 / 打卡 3 小时 / 打卡（只记时间不给也行，用任务完成情况兜底）。 */
@Component
public class ExamCheckinHandler implements CommandHandler {

    private final ExamService examService;

    public ExamCheckinHandler(ExamService examService) {
        this.examService = examService;
    }

    @Override
    public String name() {
        return "checkin";
    }

    @Override
    public String description() {
        return "考研打卡（可带时长，例如「打卡 150」或「打卡 3 小时」）";
    }

    @Override
    public List<String> aliases() {
        return List.of("打卡", "今日打卡", "考研打卡");
    }

    @Override
    public String handle(String args, String userId) {
        return examService.checkin(userId, parseMinutes(args), null);
    }

    /** 解析「150」「3小时」「2.5h」「1.5 小时」这类写法，认不出来返回 null（只记打卡） */
    static Integer parseMinutes(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        String text = args.trim().toLowerCase().replace(" ", "");
        boolean hours = text.contains("小时") || text.contains("h") || text.contains("时");
        String digits = text.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(digits);
            double minutes = hours ? value * 60 : value;
            if (minutes <= 0 || minutes > 24 * 60) {
                return null;
            }
            return (int) Math.round(minutes);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
