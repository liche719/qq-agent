package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.MessageIdempotency;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.command.CommandRegistry;
import com.liche.wechatagent.document.DocumentExtractionService;
import com.liche.wechatagent.log.ConversationTraceLogger;
import com.liche.wechatagent.media.MediaToolContextService;
import com.liche.wechatagent.memory.MemoryExtractionScheduler;
import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.user.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorCommandTest {

    @Test
    void directCommandsReplyNormallyWithoutCreatingAStreamingSession() {
        ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();
        PerUserExecutors perUserExecutors = new PerUserExecutors(1, 10, 1);
        try {
            MessageIdempotency idempotency = mock(MessageIdempotency.class);
            UserService userService = mock(UserService.class);
            CommandRegistry commandRegistry = mock(CommandRegistry.class);
            MemoryExtractionScheduler extractionScheduler = mock(MemoryExtractionScheduler.class);
            WeChatChannel channel = mock(WeChatChannel.class);
            when(idempotency.tryAcquire("user-a", "message-a")).thenReturn(true);
            when(channel.channel()).thenReturn("qq");
            when(commandRegistry.tryHandle("/help", "user-a")).thenReturn(Optional.of("可用指令"));

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    idempotency,
                    userService,
                    perUserExecutors,
                    commandRegistry,
                    mock(ContextStore.class),
                    mock(MemoryLoader.class),
                    mock(AgentLoop.class),
                    new InboundMessageBatcher(batchScheduler, 0),
                    extractionScheduler,
                    new ToolStatusService(List.of(channel)),
                    mock(DocumentExtractionService.class),
                    mock(MediaToolContextService.class),
                    new ConversationTraceLogger(false, 100),
                    List.of(channel));

            orchestrator.onInbound(InboundMessage.text("message-a", "user-a", "/help", "qq", "qq"));

            verify(channel, timeout(1_000)).sendTextReplyFrom("qq", "user-a", "message-a", "可用指令");
            verify(channel, never()).createStreamSink("user-a", "message-a");
            verify(extractionScheduler, never()).cancelPending("user-a");
        } finally {
            perUserExecutors.shutdown();
            batchScheduler.shutdownNow();
        }
    }
}
