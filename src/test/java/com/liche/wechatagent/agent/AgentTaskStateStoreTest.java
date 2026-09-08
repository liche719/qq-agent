package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskStateStoreTest {
    @Test
    void completeTextIsReplayableButTruncatedTextIsNot() {
        assertEquals("true", snapshot(InboundMessage.text("message", "user", "a".repeat(8000))).get("replaySafe"));
        Map<?, ?> clipped = snapshot(InboundMessage.text("message", "user", "a".repeat(8001)));
        assertEquals("false", clipped.get("replaySafe"));
        assertEquals(8001, clipped.get("inputContent").toString().length());
    }

    @Test
    void imagesAndQuotesCannotBeReplayedAsPlainText() {
        assertEquals("false", snapshot(InboundMessage.textWithImages("message", "user", "image",
                "bot", "qq", List.of("https://example.com/image"))).get("replaySafe"));
        assertEquals("false", snapshot(InboundMessage.textWithQuote("message", "user", "quote",
                "bot", "qq", List.of(), List.of(), "original", List.of(), List.of())).get("replaySafe"));
    }

    @Test
    void missingInputCannotClaimRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("agent:task:task")).thenReturn(Map.of(
                "status", "UNKNOWN_RESULT", "replaySafe", "true", "recoverySchema", "2"));
        assertTrue(new AgentTaskStateStore(redis, 168).claimManualRetry("task").isEmpty());
        verify(redis, never()).opsForValue();
    }

    private Map<?, ?> snapshot(InboundMessage message) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        HashOperations<String, Object, Object> hashes = redis.opsForHash();
        AtomicReference<Map<?, ?>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(hashes).putAll(eq("agent:task:task"), anyMap());
        new AgentTaskStateStore(redis, 168).captureInput("task", InboundMessageBatch.single(message));
        return captured.get();
    }
}
