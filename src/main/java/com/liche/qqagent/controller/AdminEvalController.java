package com.liche.qqagent.controller;

import com.liche.qqagent.agent.AgentEvalService;
import com.liche.qqagent.memory.MemoryEvalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 记忆评估入口（2026-10-26）。
 *
 * <p><b>默认关着</b>（`agent.eval-enabled=false`）：生产不开，需要本地跑时才设
 * `AGENT_EVAL_ENABLED=true`。即使用户传错 id 也只会操作 `eval-` 前缀的用户，
 * 跑完自动清场（见 {@link MemoryEvalService}）。
 *
 * <p>用法（本地 18080）：
 * <pre>
 * curl -X POST "http://127.0.0.1:18080/api/admin/eval/memory?userId=eval-run1"
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/eval")
public class AdminEvalController {

    private final MemoryEvalService evalService;
    private final AgentEvalService agentEvalService;

    public AdminEvalController(MemoryEvalService evalService, AgentEvalService agentEvalService) {
        this.evalService = evalService;
        this.agentEvalService = agentEvalService;
    }

    /** 工具选择 + 多轮连续性（2026-10-27）：只读决策，不执行工具、无副作用 */
    @PostMapping("/agent")
    public Map<String, Object> agent(@RequestParam(defaultValue = "eval-agent") String userId) {
        return agentEvalService.run(userId);
    }

    @PostMapping("/memory")
    public Map<String, Object> memory(@RequestParam String userId,
                                      @RequestParam(defaultValue = "false") boolean keep) {
        return evalService.run(userId, keep);
    }
}
