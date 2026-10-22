package com.liche.qqagent.exception;

/**
 * 模型服务**明确拒绝**了我们（401 密钥无效 / 402 余额不足），重试也不会有用。
 *
 * <p>为什么要单独一个类型（2026-10-15 加）：这类失败不该和"上游抖了一下"混在一起——
 * 抖动可以重试、可以用一句"稍后再试"糊过去，而**余额不足/密钥失效是我们这边要有人去处理的事**，
 * 用户等多久都不会好。所以由它一路带到 {@code AgentOrchestrator}，
 * 让用户看到一句说得清的话（而不是笼统的"出了点小问题"），同时由 {@code LlmHealthState} 触发运维告警。
 *
 * <p>{@link #getMessage()} 就是**给用户看**的那句话，不要往里塞堆栈或内部细节。
 */
public class LlmUnavailableException extends RuntimeException {

    /** 上游返回的状态码（401 / 402） */
    private final int status;

    public LlmUnavailableException(int status, String userMessage) {
        super(userMessage);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
