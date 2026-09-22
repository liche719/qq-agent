package com.liche.wechatagent.config;

/**
 * LLM 调用事件的接收端（2026-09-21 加）——与 {@link LlmUsageSink} 同一套解耦思路：
 * **模型层只负责广播事实**（场景、成败、耗时、用量），谁拿它做什么由实现决定。
 *
 * <p>与 {@link LlmUsageSink} 的分工：那个只收"用了多少"，用于进程内计数与按钱熔断；
 * 这个收的是"这一跳的完整事实"，用于**落库审计**（{@code llm_call_audit}）。
 * 分成两个接口是为了不改动已上线实现的签名（{@code LlmUsageSink.accept} 不带场景）。
 *
 * <p><b>约定：实现必须自己吞掉异常</b>——记账失败绝不能把模型调用带崩。调用方也会兜一层。
 */
public interface LlmCallSink {

    void acceptCall(LlmCallEvent event);
}
