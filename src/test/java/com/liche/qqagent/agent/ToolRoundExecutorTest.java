package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionOutcome;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行编排（2026-10-27 从 AgentLoop 拆出）：并行分段与**结果顺序**是这里最要紧的两件事。
 * 顺序一旦错位，模型会拿到"先读 A 的结果却挂在 B 的名字下"，比慢更糟。
 */
class ToolRoundExecutorTest {

    private static ToolExecutionRequest request(String name) {
        return ToolExecutionRequest.builder().id("id-" + name).name(name).arguments("{}").build();
    }

    /** 记录执行顺序：可指定哪些工具"允许并行／会失败" */
    private record Trace(List<String> executed, List<String> recorded) {
    }

    private static Trace run(List<ToolExecutionRequest> requests,
                             java.util.Set<String> parallelAllowed, java.util.Set<String> failing,
                             int poolSize) {
        List<String> executed = new CopyOnWriteArrayList<>();
        List<String> recorded = new ArrayList<>();
        ToolRoundExecutor.Hooks hooks = new ToolRoundExecutor.Hooks(
                request -> parallelAllowed.contains(request.name()),
                request -> {
                    executed.add(request.name());
                    return failing.contains(request.name())
                            ? ToolExecutionOutcome.failure("boom", 0)
                            : ToolExecutionOutcome.success("ok", 1);
                },
                request -> {
                    // 进度这里不关心
                },
                (request, outcome) -> recorded.add(request.name() + "=" + (outcome.successful() ? "ok" : "fail")));
        new ToolRoundExecutor(poolSize, 30).run(requests, hooks);
        return new Trace(executed, recorded);
    }

    @Test
    void keepsResultOrderEvenWhenParallel() {
        Trace trace = run(List.of(request("searchA"), request("searchB"), request("searchC"),
                        request("writeFile")),
                java.util.Set.of("searchA", "searchB", "searchC"), java.util.Set.of(), 3);

        // 结果必须按请求顺序回填，和真实执行顺序无关
        assertEquals(List.of("searchA=ok", "searchB=ok", "searchC=ok", "writeFile=ok"), trace.recorded());
        assertEquals(4, trace.executed().size());
    }

    @Test
    void sideEffectToolBreaksTheParallelRun() {
        // A、B 可并行；write 不可并行；C 可并行 —— 应该分成 [A,B] 并行、[write] 串行、[C] 单独
        Trace trace = run(List.of(request("searchA"), request("searchB"), request("writeFile"),
                        request("searchC")),
                java.util.Set.of("searchA", "searchB", "searchC"), java.util.Set.of(), 3);

        assertEquals(List.of("searchA=ok", "searchB=ok", "writeFile=ok", "searchC=ok"), trace.recorded());
    }

    @Test
    void failureIsReportedButDoesNotStopTheBatch() {
        Trace trace = run(List.of(request("badTool"), request("goodTool")),
                java.util.Set.of(), java.util.Set.of("badTool"), 2);

        assertEquals(List.of("badTool=fail", "goodTool=ok"), trace.recorded());
    }

    @Test
    void emptyOrSingleRequestIsHandled() {
        assertEquals(List.of(), run(List.of(), java.util.Set.of(), java.util.Set.of(), 2).recorded());
        assertEquals(1, run(List.of(request("only")), java.util.Set.of(), java.util.Set.of(), 2).executed().size());
    }
}
