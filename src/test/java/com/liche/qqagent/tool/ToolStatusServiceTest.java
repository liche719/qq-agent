package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 当前用户上下文（2026-10-28 去重：9 个工具类各写了一份 requireCurrentUser）。
 * 这里只测"取不到就抛"这条——它是工具在错误上下文里跑时的唯一防线。
 */
class ToolStatusServiceTest {

    @Test
    void returnsNullOutsideATurn() {
        ToolStatusService service = new ToolStatusService(java.util.List.of());
        assertNull(service.currentUserId(), "没绑定上下文时 currentUserId 返回 null");
    }

    @Test
    void requireFailsOutsideATurn() {
        ToolStatusService service = new ToolStatusService(java.util.List.of());
        IllegalStateException error = assertThrows(IllegalStateException.class, service::requireCurrentUserId);
        assertEquals("当前用户上下文不可用，无法执行该操作。", error.getMessage());
    }
}

/** 追加：上下文快照（2026-10-29，给"把工具挪到别的线程执行"用）。 */
class ToolStatusContextSnapshotTest {

    @Test
    void snapshotsAndRestoresContextAcrossThreads() throws Exception {
        ToolStatusService service = new ToolStatusService(java.util.List.of());
        service.bind("u1", "reply-1", "bot-1", "qq");
        try {
            String userId = service.currentUserId();
            String botId = service.currentBotId();
            String channel = service.currentChannel();
            String replyTo = service.currentReplyToMsgId();

            // 模拟"工具跑在另一个线程"：池线程里必须重新 bind，否则当前用户是 null
            java.util.concurrent.atomic.AtomicReference<String> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                seen.set(service.currentUserId());
                service.bind(userId, replyTo, botId, channel);
                try {
                    seen.set(seen.get() + "/" + service.currentUserId());
                } finally {
                    service.unbind();
                }
            });
            worker.start();
            worker.join();

            assertEquals("null/u1", seen.get(), "池线程里不重新 bind 就是 null，bind 之后才对");
        } finally {
            service.unbind();
        }
    }
}
