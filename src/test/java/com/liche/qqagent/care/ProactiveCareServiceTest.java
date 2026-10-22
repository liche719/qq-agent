package com.liche.qqagent.care;

import com.liche.qqagent.channel.ChatChannel;
import com.liche.qqagent.log.UserLogService;
import com.liche.qqagent.memory.MemoryService;
import com.liche.qqagent.user.UserProfile;
import com.liche.qqagent.user.UserProfileRepository;
import com.liche.qqagent.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProactiveCareServiceTest {

    @Test
    void defaultsOnCommandToWeeklyAndKeepsOptInExplicit() {
        UserService userService = mock(UserService.class);
        UserProfile profile = new UserProfile("u1", "persona");
        when(userService.get("u1")).thenReturn(profile);
        ProactiveCareService service = new ProactiveCareService(mock(UserProfileRepository.class), userService,
                mock(MemoryService.class), List.<ChatChannel>of(),
                mock(UserLogService.class));

        String result = service.configure("u1", "on");

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userService).configureProactiveCare(eq("u1"), eq(true), eq(ProactiveCareService.WEEKLY), next.capture());
        assertEquals(20, next.getValue().getHour());
        assertEquals(30, next.getValue().getMinute());
        assertTrue(result.contains("每周"));
    }
}
