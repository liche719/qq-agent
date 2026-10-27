package com.liche.qqagent.bilibili;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注册的回归测试（2026-10-28）。
 *
 * <p><b>修的真实 bug</b>：`recallWatchedVideos` 的 `@Tool` 注解被错挂在私有方法 `dedupByBvid` 上。
 * 注册只扫 `getMethods()`（public），于是这个工具**从来没被注册**——生产日志一直是 64 个而不是 65 个，
 * 模型的表现是"它不能回看自己看过的视频"，用户只会觉得这个能力不存在。
 *
 * <p>这里锁两件事：① 该有注解的 public 方法确实有；② **没有 `@Tool` 落在非 public 方法上**
 * （落在私有方法上不会报错，只会被静默忽略——这正是它藏了这么久的原因）。
 */
class BilibiliToolRegistrationTest {

    @Test
    void recallWatchedVideosIsATool() throws Exception {
        Method method = BilibiliTool.class.getMethod("recallWatchedVideos", String.class, Integer.class);
        assertNotNull(method.getAnnotation(Tool.class), "回看自己看过的视频必须是可用工具");
    }

    @Test
    void noToolAnnotationOnNonPublicMethods() {
        List<String> misconfigured = new ArrayList<>();
        for (Method method : BilibiliTool.class.getDeclaredMethods()) {
            if (method.getAnnotation(Tool.class) != null
                    && !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                misconfigured.add(method.getName());
            }
        }
        assertTrue(misconfigured.isEmpty(),
                "@Tool 落在非 public 方法上会被静默忽略，必须挪到注册方法上：" + misconfigured);
    }
}
