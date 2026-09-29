package com.liche.wechatagent.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面板上给某个调用场景设的**思考强度覆盖值**（2026-09-29，DDL 见
 * {@code deploy/postgres/V19__create_llm_scenario_setting.sql}）。
 *
 * <p>没有行 = 用 {@code application.yml} 里 {@code llm.reasoning-effort} 的默认值；
 * 面板选「默认」就是删掉这一行，而不是存一个特殊字符串——这样"回落到配置"的语义只有一个来源。
 */
@Entity
@Table(name = "llm_scenario_setting")
@Getter
@Setter
@NoArgsConstructor
public class LlmScenarioSetting {

    /** 场景名（{@link LlmScenario#label()}，如 {@code dialog} / {@code extract}） */
    @Id
    @Column(name = "scenario", length = 32)
    private String scenario;

    /** low / medium / high */
    @Column(name = "reasoning_effort", length = 16, nullable = false)
    private String reasoningEffort;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
