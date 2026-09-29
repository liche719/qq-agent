package com.liche.wechatagent.config;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmScenarioSettingRepository extends JpaRepository<LlmScenarioSetting, String> {
}
