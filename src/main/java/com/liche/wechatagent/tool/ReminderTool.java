package com.liche.wechatagent.tool;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.reminder.ReminderParseService;
import com.liche.wechatagent.reminder.ReminderService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具2：定时提醒工具集。
 * parseReminder：@Tool 仅负责把自然语言解析为结构化数据，入库/校验/调度全部交由后端业务层（ReminderService）。
 */
@Component
public class ReminderTool {

    private final ReminderParseService parseService;
    private final ReminderService reminderService;
    private final ToolStatusService statusService;

    public ReminderTool(ReminderParseService parseService,
                        ReminderService reminderService,
                        ToolStatusService statusService) {
        this.parseService = parseService;
        this.reminderService = reminderService;
        this.statusService = statusService;
    }

    @Tool(value = "解析用户的提醒需求并创建定时提醒。用户提到'提醒我/帮我记着/XX点叫我/定时/每天/每周'等意图时调用。参数 description 是用户的原话。")
    public String parseReminder(String description) {
        ReminderParseService.ParsedReminder parsed = parseService.parse(description);
        return reminderService.createFromParsed(parsed, statusService.currentUserId());
    }

    @Tool(value = "查询当前用户所有待执行的定时提醒任务。")
    public String listReminders() {
        return reminderService.listPendingText(statusService.currentUserId());
    }

    @Tool(value = "取消一个定时提醒任务。参数 reminderId 为提醒的 ID（可通过 listReminders 查询）。")
    public String cancelReminder(Long reminderId) {
        return reminderService.cancel(statusService.currentUserId(), reminderId);
    }
}
