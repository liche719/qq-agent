package com.liche.wechatagent.tool;

/**
 * 「我是一个给大模型用的工具类」的标记接口。
 *
 * <p>为什么要这个接口：{@link ToolRegistry} 之前是把每个工具类**逐个写在构造器参数里**的，
 * 于是每加一个工具都要动一次公共类（加模块的人总得改别人的文件，容易漏、也容易冲突）。
 * 现在改成 Spring 注入 {@code List<AgentToolProvider>}——**新工具只要在自己的包里
 * 实现这个接口 + 标 {@code @Component} + 方法上写 {@code @Tool} 就会被自动注册**，
 * 不用再碰 {@link ToolRegistry}。
 *
 * <p>代价是"忘了 implements"的工具会静默不注册，所以 {@link ToolRegistry} 启动时会打印
 * 「工具注册完成：N 个类 / M 个方法」，加完工具请核对一眼日志里的数字和名字。
 */
public interface AgentToolProvider {
}
