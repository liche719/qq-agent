package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.reminder.ReminderService;
import org.springframework.stereotype.Component;

/** /reminders：列出当前用户所有待执行的定时提醒 */
@Component
public class RemindersHandler implements CommandHandler {

    private final ReminderService reminderService;

    public RemindersHandler(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Override
    public String name() {
        return "reminders";
    }

    @Override
    public String description() {
        return "列出你所有待执行的定时提醒";
    }

    @Override
    public String handle(String args, String userId) {
        return reminderService.listPendingText(userId);
    }
}
