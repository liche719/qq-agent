package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.agent.AgentTaskStateStore;
import com.liche.wechatagent.channel.qq.QqChannel;
import com.liche.wechatagent.log.OperationLogRepository;
import com.liche.wechatagent.memory.*;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.reminder.ReminderTaskRepository;
import com.liche.wechatagent.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDashboardControllerTest {
    private final UserProfileRepository users = mock(UserProfileRepository.class);
    private final AgentTaskStateStore tasks = mock(AgentTaskStateStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Scheduler scheduler = mock(Scheduler.class);
    private final DashboardMetricHistory history = new DashboardMetricHistory();

    private AdminDashboardController controller() {
        ObjectProvider<QqChannel> channels = mock(ObjectProvider.class);
        return new AdminDashboardController(new HealthController("UTC"), tasks, mock(AgentOrchestrator.class),
                users, mock(ConversationMemoryRepository.class), mock(EpisodicMemoryRepository.class),
                mock(UserCoreMemoryRepository.class), mock(UserWorkMemoryRepository.class),
                mock(ReminderTaskRepository.class), mock(OperationLogRepository.class), channels, jdbc, redis,
                scheduler, mock(StoredMediaRepository.class), history);
    }

    @Test
    void databaseFailurePreservesResourceDataAndDoesNotLeakException() {
        when(users.count()).thenThrow(new IllegalStateException("secret-password /private/database"));
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException());
        Map<String, Object> result = controller().overview();
        assertEquals("DEGRADED", result.get("status"));
        assertNotNull(result.get("jvm"));
        assertNull(result.get("users"));
        assertTrue(((Map<?, ?>) result.get("moduleErrors")).containsKey("users"));
        assertFalse(result.toString().contains("secret-password"));
        assertFalse(result.toString().contains("/private/database"));
        assertTrue(history.snapshot().isEmpty());
    }

    @Test
    void redisConnectionClosesAndOnlyScheduledSamplingAddsHistory() throws Exception {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(scheduler.isStarted()).thenReturn(true);
        AdminDashboardController controller = controller();
        assertEquals("UP", controller.overview().get("status"));
        verify(connection).close();
        assertTrue(controller.history().isEmpty());
        controller.scheduledSample();
        assertEquals(1, controller.history().size());
        verify(connection, times(2)).close();
    }

    @Test
    void expiredTaskIdsAreExcludedAndHugePageDoesNotOverflow() {
        when(tasks.findTaskIds()).thenReturn(Set.of("expired", "live"));
        when(tasks.find("expired")).thenReturn(Map.of());
        when(tasks.find("live")).thenReturn(Map.of("status", "FAILED"));
        Map<String, Object> result = controller().taskList("", "", Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1, result.get("total"));
        assertTrue(((List<?>) result.get("items")).isEmpty());
        assertEquals(1, ((List<?>) controller().taskList("FAILED", "", -1, -1).get("items")).size());
    }
}
