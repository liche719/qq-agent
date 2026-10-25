package com.liche.qqagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 失败分类（2026-10-26）：瞬时错误不该触发反思，策略失败才该 */
class FailureKindTest {

    @Test
    void transientErrorsAreRecognised() {
        assertEquals(FailureKind.TRANSIENT, FailureKind.classify("Read timed out", 1));
        assertEquals(FailureKind.TRANSIENT, FailureKind.classify("连接被重置", 1));
        assertEquals(FailureKind.TRANSIENT, FailureKind.classify("HTTP 503 Service Unavailable", 1));
        assertEquals(FailureKind.TRANSIENT, FailureKind.classify("rate limit exceeded", 1));
    }

    @Test
    void everythingElseIsFixable() {
        assertEquals(FailureKind.FIXABLE, FailureKind.classify("缺少参数 city", 1));
        assertEquals(FailureKind.FIXABLE, FailureKind.classify("没找到相关内容", 2));
        assertEquals(FailureKind.FIXABLE, FailureKind.classify(null, 0));
    }
}
