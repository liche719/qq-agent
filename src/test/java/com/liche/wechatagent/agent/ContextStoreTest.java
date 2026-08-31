package com.liche.wechatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void fallsBackToBoundedInMemoryContextWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> operations = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(operations);
        when(operations.leftPush(any(), any())).thenThrow(new IllegalStateException("redis unavailable"));
        when(operations.range(any(), anyLong(), anyLong())).thenThrow(new IllegalStateException("redis unavailable"));
        ContextStore store = new ContextStore(redis, new ObjectMapper(), 2, 1);

        store.push("user-a", "user", "你好", List.of("m-1"));

        List<ContextTurn> turns = store.getRecent("user-a");
        assertEquals(1, turns.size());
        assertEquals("你好", turns.getFirst().text());
        assertEquals(List.of("m-1"), turns.getFirst().sourceMessageIds());
    }
}
