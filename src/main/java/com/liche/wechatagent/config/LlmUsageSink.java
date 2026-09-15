package com.liche.wechatagent.config;

/**
 * "有人用了一次模型"这件事的接收端（2026-09-15 加，为"计费与模型解耦"）。
 *
 * <p>模型实现只依赖这一个方法，**不认识钱、不认识预算、也不认识自主模块**。
 * 想加一个新的消费者（按用户分账、写审计、喂监控）就再写一个实现，模型层一行都不用改。
 *
 * <p>约定：实现必须**不抛异常**——记账失败不能把模型调用带崩（模型类会吞掉异常，但别指望它）。
 */
public interface LlmUsageSink {

    void accept(LlmUsage usage);
}
