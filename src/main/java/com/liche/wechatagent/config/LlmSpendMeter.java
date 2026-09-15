package com.liche.wechatagent.config;

/**
 * 花销读数（2026-09-15 加，为"计费与模型解耦"）。
 *
 * <p>谁需要"知道花了多少"就依赖这个接口，而不是具体的记账类——
 * 典型是两条熔断：{@code AgentLoop} 按钱收尾、自主模块按日预算决定要不要动。
 * 它们**不该认识"账本"是怎么实现的**（是内存累计还是查库），也不该认识钱的单价。
 */
public interface LlmSpendMeter {

    /** 进程内累计花销（元）。只用于算差值：某次作业花了多少 = 前后之差。 */
    double totalYuan();

    long cacheHitTokens();

    long cacheMissTokens();
}
