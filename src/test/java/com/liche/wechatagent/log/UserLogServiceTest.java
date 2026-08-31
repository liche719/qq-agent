package com.liche.wechatagent.log;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserLogServiceTest {

    @Test
    void persistsOnlySafeStructuredMetadata() {
        OperationLogRepository repository = mock(OperationLogRepository.class);
        UserLogService service = new UserLogService(repository);

        service.record("qq-private-user", "REMINDER_CREATE",
                Map.of("reminderId", 42L, "content", "明天向老师说明个人情况"));

        ArgumentCaptor<OperationLog> captured = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository).save(captured.capture());
        assertEquals("content=[redacted],reminderId=42", captured.getValue().getDetail());
        assertFalse(captured.getValue().getDetail().contains("个人情况"));
    }

    @Test
    void createsAStableNonRevealingLogScope() {
        String scope = UserScope.forUser("qq-private-user");

        assertTrue(scope.matches("user-[0-9a-f]{16}"));
        assertFalse(scope.contains("private-user"));
        assertEquals(scope, UserScope.forUser("qq-private-user"));
    }
}
