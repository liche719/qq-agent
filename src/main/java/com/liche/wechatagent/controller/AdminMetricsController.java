package com.liche.wechatagent.controller;

import com.liche.wechatagent.metrics.RuntimeMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 运行期指标：LLM 与搜索的成功率、耗时、最近错误（鉴权由 AdminAccessFilter 统一处理） */
@RestController
@RequestMapping("/api/admin")
public class AdminMetricsController {

    private final RuntimeMetrics metrics;

    public AdminMetricsController(RuntimeMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics/runtime")
    public Map<String, Object> runtime() {
        return metrics.snapshot();
    }
}
