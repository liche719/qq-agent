package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.MessageIdempotency;
import com.liche.wechatagent.command.CommandRegistry;
import com.liche.wechatagent.document.DocumentExtractionService;
import com.liche.wechatagent.log.ConversationTraceLogger;
import com.liche.wechatagent.media.MediaToolContextService;
import com.liche.wechatagent.memory.ConversationMemoryService;
import com.liche.wechatagent.memory.MemoryExtractionScheduler;
import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorMemoryTest {

    @Test
    void doesNotPersistNewConversationEvidenceWhenAutomaticMemoryIsDisabled() {
        ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();
        PerUserExecutors perUserExecutors = new PerUserExecutors(1, 10, 1);
        try {
            MessageIdempotency idempotency = mock(MessageIdempotency.class);
            UserService userService = mock(UserService.class);
            CommandRegistry commands = mock(CommandRegistry.class);
            ContextStore contextStore = mock(ContextStore.class);
            MemoryLoader memoryLoader = mock(MemoryLoader.class);
            AgentLoop agentLoop = mock(AgentLoop.class);
            MemoryExtractionScheduler scheduler = mock(MemoryExtractionScheduler.class);
            MediaToolContextService mediaContext = mock(MediaToolContextService.class);
            ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
            UserProfile profile = new UserProfile("user-a", "陪伴助手");
            profile.setMemoryEnabled(false);
            when(idempotency.tryAcquire("user-a", "message-a")).thenReturn(true);
            when(userService.get("user-a")).thenReturn(profile);
            when(commands.tryHandle("普通聊天", "user-a")).thenReturn(Optional.empty());
            when(memoryLoader.load("user-a", "普通聊天"))
                    .thenReturn(new MemoryLoader.LoadedMemory("（暂无）", "（暂无）"));
            when(contextStore.getRecent("user-a")).thenReturn(List.of());
            when(mediaContext.promptSection()).thenReturn("");
            when(agentLoop.chat(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                    anyList(), anyString(), anyList(), anyList(), any())).thenReturn("好的");

            AgentOrchestrator orchestrator = new AgentOrchestrator(idempotency, userService, perUserExecutors,
                    commands, contextStore, memoryLoader, agentLoop, new InboundMessageBatcher(batchScheduler, 0),
                    scheduler, new ToolStatusService(List.of()), mock(DocumentExtractionService.class), mediaContext,
                    new ConversationTraceLogger(false, 100), List.of(), conversationMemory);

            String reply = orchestrator.onInboundSync(InboundMessage.text("message-a", "user-a", "普通聊天", "qq", "qq"));

            assertEquals("好的", reply);
            verify(conversationMemory, never()).record(anyString(), anyString(), anyString(), anyString(), anyList(), any());
            verify(scheduler, never()).schedule(anyString());
        } finally {
            perUserExecutors.shutdown();
            batchScheduler.shutdownNow();
        }
    }
}
