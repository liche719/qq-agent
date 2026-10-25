package com.liche.qqagent.controller;

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

    public AdminEvalController(MemoryEvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/memory")
    public Map<String, Object> memory(@RequestParam String userId,
                                      @RequestParam(defaultValue = "false") boolean keep) {
        return evalService.run(userId, keep);
    }
}
