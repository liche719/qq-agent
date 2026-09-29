package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.LlmScenario;
import com.liche.wechatagent.config.LlmScenarioEffortService;
import com.liche.wechatagent.config.LlmScenarioSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个调用场景的**思考强度**（面板「模型与搜索」页的滑块，2026-09-29 加）。
 *
 * <p>为什么要做成可调：思考强度是"想试一下、不合适马上改回来"的参数，而配置文件的默认值
 * 改一次就要改 {@code .env} + 重启容器（还会打断正在跑的对话）。这里给一个落库的覆盖层，
 * 改完**立刻生效**；配置默认值仍在 {@code application.yml}，作为没有覆盖时的兜底。
 *
 * <p>鉴权由 {@code AdminAccessFilter} 统一处理（{@code /api/admin/**} 一律要口令）。
 */
@RestController
@RequestMapping("/api/admin/llm")
public class AdminLlmController {

    private final LlmScenarioSettings settings;
    private final LlmScenarioEffortService overrides;

    public AdminLlmController(LlmScenarioSettings settings, LlmScenarioEffortService overrides) {
        this.settings = settings;
        this.overrides = overrides;
    }

    /** 全部场景 + 各自的「配置默认 / 面板覆盖 / 实际生效」 */
    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        return snapshot();
    }

    /**
     * 改一个场景的思考强度。
     *
     * <p>请求体 {@code {"scenario":"extract","effort":"high"}}；{@code effort} 传空串 = 恢复成配置默认值。
     */
    @PostMapping("/scenarios")
    public ResponseEntity<Map<String, Object>> saveScenario(@RequestBody(required = false) Map<String, String> body) {
        String scenario = body == null ? null : body.get("scenario");
        String effort = body == null ? null : body.get("effort");
        if (!overrides.save(scenario, effort)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("accepted", false);
            error.put("message", "场景名或思考强度不认识（档位只能是 low / medium / high，或留空表示恢复默认）");
            error.putAll(snapshot());
            return ResponseEntity.badRequest().body(error);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("message", "已生效");
        result.putAll(snapshot());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> snapshot() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> applied = overrides.current();
        for (LlmScenario scenario : LlmScenario.values()) {
            String configured = settings.configuredEffortFor(scenario);
            String override = applied.get(scenario.label());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", scenario.label());
            row.put("configured", configured == null ? LlmScenarioEffortService.DEFAULT : configured);
            row.put("override", override == null ? LlmScenarioEffortService.DEFAULT : override);
            row.put("effective", settings.reasoningEffortFor(scenario) == null
                    ? LlmScenarioEffortService.DEFAULT : settings.reasoningEffortFor(scenario));
            row.put("fromPanel", override != null);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", LlmScenarioEffortService.EFFORTS);
        result.put("scenarios", rows);
        return result;
    }
}
