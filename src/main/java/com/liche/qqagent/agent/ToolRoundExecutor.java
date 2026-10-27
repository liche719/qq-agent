package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionOutcome;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 工具执行编排（2026-10-27 从 {@code AgentLoop} 拆出）。
 *
 * <p><b>拆出来的理由</b>：AgentLoop 1252 行，里面既有"怎么跟模型对话"，又有"怎么把一批工具跑完"。
 * 后者是一段自洽的调度逻辑（串行/并行、顺序回填、超时兜底），和对话流程没有耦合，
 * 是最适合先搬出去的一块。
 *
 * <p><b>职责</b>：给定一批工具请求，决定哪些能并行（连续的"只读 + FAST"），
 * 其余串行；**结果严格按请求顺序回填**——模型看到的顺序必须和它发出的顺序一致，
 * 否则"先搜 A 再读 A 的结果"这种依赖会错位。
 *
 * <p>真正"跑一个工具"的动作由调用方通过 {@link Hooks} 提供：这样这个类不依赖 ToolRegistry、
 * 也不依赖上下文注入/埋点，纯调度。
 */
public class ToolRoundExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolRoundExecutor.class);

    /**
     * 调用方要提供的能力：能不能并行、真正执行、报进度、记录结果。
     * 用 record 装四个函数，调用方一行就能给全（不必写匿名类）。
     */
    public record Hooks(java.util.function.Predicate<ToolExecutionRequest> parallelSafe,
                        java.util.function.Function<ToolExecutionRequest, ToolExecutionOutcome> execute,
                        java.util.function.Consumer<ToolExecutionRequest> announce,
                        java.util.function.BiConsumer<ToolExecutionRequest, ToolExecutionOutcome> record) {
    }

    private final int poolSize;
    private final long timeoutSeconds;
    private volatile ExecutorService pool;

    public ToolRoundExecutor(int poolSize, long timeoutSeconds) {
        this.poolSize = Math.max(1, Math.min(4, poolSize));
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    /** 把一批请求跑完（并行段和串行段交替处理） */
    public void run(List<ToolExecutionRequest> requests, Hooks hooks) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < requests.size()) {
            int end = index;
            while (end < requests.size() && hooks.parallelSafe().test(requests.get(end))) {
                end++;
            }
            if (end - index > 1) {
                runBatchParallel(requests.subList(index, end), hooks);
                index = end;
                continue;
            }
            ToolExecutionRequest request = requests.get(index);
            hooks.announce().accept(request);
            hooks.record().accept(request, hooks.execute().apply(request));
            index++;
        }
    }

    private void runBatchParallel(List<ToolExecutionRequest> batch, Hooks hooks) {
        for (ToolExecutionRequest request : batch) {
            hooks.announce().accept(request);
        }
        List<Future<ToolExecutionOutcome>> futures = new ArrayList<>(batch.size());
        for (ToolExecutionRequest request : batch) {
            futures.add(pool().submit(() -> hooks.execute().apply(request)));
        }
        List<ToolExecutionOutcome> outcomes = new ArrayList<>(batch.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS));
            } catch (Exception exception) {
                log.warn("并行工具执行失败 name={}: {}", batch.get(i).name(), exception.getMessage());
                outcomes.add(ToolExecutionOutcome.failure("并行执行失败：" + exception.getMessage(), 0));
            }
        }
        // 结果按原顺序回填，别让模型的依赖错位
        for (int i = 0; i < batch.size(); i++) {
            hooks.record().accept(batch.get(i), outcomes.get(i));
        }
        log.info("并行执行 {} 个只读工具：{}", batch.size(),
                batch.stream().map(ToolExecutionRequest::name).toList());
    }

    private ExecutorService pool() {
        if (pool == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(poolSize, runnable -> {
                        Thread thread = new Thread(runnable, "tool-parallel");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return pool;
    }
}
