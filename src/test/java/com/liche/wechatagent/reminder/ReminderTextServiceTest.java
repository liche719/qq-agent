package com.liche.wechatagent.reminder;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReminderTextServiceTest {

    @Test
    void rendersWeekdayFromActualTriggerTime() {
        ReminderTextService service = new ReminderTextService();

        assertEquals("9月2日（周三）20:00", service.absoluteTime(LocalDateTime.of(2026, 9, 2, 20, 0)));
    }

    @Test
    void removesOnlyTheOuterReminderRequestPrefix() {
        ReminderTextService service = new ReminderTextService();

        assertEquals("去打印申请表", service.displayContent("明天上午10点提醒我去打印申请表"));
        assertEquals("开会", service.displayContent("提醒我开会"));
        assertEquals("明天要带课本", service.displayContent("明天要带课本"));
    }
}
