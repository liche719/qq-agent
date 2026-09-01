package com.liche.wechatagent.reminder;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReminderParseServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void rejectsModelDateThatConflictsWith明天() {
        ChatModel model = mock(ChatModel.class);
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDate wrongDate = now.toLocalDate().plusDays(2);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                "{\"content\":\"交作业\",\"triggerAt\":\"" + wrongDate + " 09:00\","
                        + "\"prewarmMinutes\":10,\"repeatCron\":null,\"missing\":[]}");

        ReminderParseService service = new ReminderParseService(model);
        ReminderParseService.ParsedReminder parsed = service.parse("明天上午9点提醒我交作业");

        assertTrue(parsed.missing().stream().anyMatch(value -> value.contains("日期解析")));
    }

    @Test
    void rejectsModelWeekdayThatConflictsWithDescription() {
        ChatModel model = mock(ChatModel.class);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate nextMonday = today.plusDays((8 - today.getDayOfWeek().getValue()) % 7 == 0
                ? 7 : (8 - today.getDayOfWeek().getValue()) % 7);
        LocalDate wrongTuesday = nextMonday.plusDays(1);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                "{\"content\":\"开会\",\"triggerAt\":\"" + wrongTuesday + " 09:00\","
                        + "\"prewarmMinutes\":10,\"repeatCron\":null,\"missing\":[]}");

        ReminderParseService service = new ReminderParseService(model);
        ReminderParseService.ParsedReminder parsed = service.parse("下周一上午9点提醒我开会");

        assertTrue(parsed.missing().stream().anyMatch(value -> value.contains("星期解析")));
    }

    @Test
    void exposesTheConfiguredDefaultPrewarmValueToTheParser() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                "{\"content\":\"开会\",\"triggerAt\":null,\"prewarmMinutes\":null,\"repeatCron\":null,\"missing\":[\"具体时间\"]}");
        ReminderParseService service = new ReminderParseService(model, "UTC", 25);

        service.parse("提醒我开会");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(model).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("默认 25"));
        assertEquals(null, new ReminderParseService(model, "UTC", 25)
                .parse("提醒我开会").prewarmMinutes());
    }
}
